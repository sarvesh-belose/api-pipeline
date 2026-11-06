package com.crisil.apiorch.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.engine.RunStatus;
import com.crisil.apiorch.engine.StepStatus;

/**
 * Row access for {@code pipeline_run}, {@code step_execution} and {@code run_context} (US-4.1), all through
 * {@code JdbcClient} (README §5.1 rule 2). Callers hold the run lock where the rules require it. Timestamps come
 * from {@code SYSUTCDATETIME()}. Context content is encrypted with {@link ContextCipher} (US-7.6), bound to its run and entry key.
 */
@Component
public class RunRows {

    /** A {@code pipeline_run} row, as the run API and the coordinator read it. */
    public record Run(UUID id, String pipelineCode, int pipelineVersion, RunStatus status, String mode,
            String correlationId, String createdBy, Instant createdAt, Instant startedAt, Instant completedAt,
            Instant endedAt, String stopReason, String errorCode, String errorMessage, String errorStepId,
            String ownerInstance, long leaseEpoch) {
    }

    /** A {@code step_execution} row. */
    public record Step(String stepId, StepStatus status, int execNo, int attempts, Integer httpStatus,
            boolean fallbackApplied, Instant startedAt, Instant endedAt, Long durationMs, String errorCode,
            String errorMessage, String skipReason) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    private final ContextCipher cipher;

    public RunRows(JdbcClient jdbc, ObjectMapper json, ContextCipher cipher) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
    }

    /** README §5.1 rule 6: the run is created owned by this node, at epoch 1. */
    public void insertRun(UUID id, String code, int version, String mode, String correlationId, String createdBy,
            String owner, long leaseMs) {
        insertRun(id, code, version, mode, correlationId, createdBy, owner, leaseMs, null, null);
    }

    /**
     * As above, with the caller's {@code Idempotency-Key} and the input hash (US-4.7).
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when the key is already used
     */
    public void insertRun(UUID id, String code, int version, String mode, String correlationId, String createdBy,
            String owner, long leaseMs, String idempotencyKey, String requestHash) {
        jdbc.sql("""
                INSERT INTO pipeline_run (id, pipeline_code, pipeline_version, status, mode, correlation_id, created_by,
                                          created_at, owner_instance, lease_epoch, lease_until, idempotency_key,
                                          request_hash)
                VALUES (:id, :code, :version, 'ACCEPTED', :mode, :cid, :by, SYSUTCDATETIME(), :owner, 1,
                        DATEADD(MILLISECOND, :leaseMs, SYSUTCDATETIME()), :key, :hash)""")
                .param("key", idempotencyKey).param("hash", requestHash).param("id", id).param("code", code).param("version", version).param("mode", mode)
                .param("cid", correlationId).param("by", createdBy).param("owner", owner).param("leaseMs", leaseMs)
                .update();
    }

    public void insertStep(UUID runId, String stepId, String mode) {
        jdbc.sql("""
                INSERT INTO step_execution (id, run_id, step_id, exec_no, status, mode)
                VALUES (:id, :run, :step, 1, 'PENDING', :mode)""")
                .param("id", UUID.randomUUID()).param("run", runId).param("step", stepId).param("mode", mode).update();
    }

    public void putContext(UUID runId, String key, JsonNode value) {
        String stored = cipher.encrypt(runId, key, write(value));
        byte[] content = stored.getBytes(StandardCharsets.UTF_8);
        String kid = ContextCipher.kidOf(stored);
        int updated = jdbc.sql("UPDATE run_context SET content = :c, key_id = :kid, updated_at = SYSUTCDATETIME() "
                + "WHERE run_id = :run AND entry_key = :key")
                .param("c", content).param("kid", kid).param("run", runId).param("key", key).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO run_context (run_id, entry_key, content, key_id) VALUES (:run, :key, :c, :kid)")
                    .param("run", runId).param("key", key).param("c", content).param("kid", kid).update();
        }
    }

    /** @throws ContextCipher.DecryptionException when an entry was tampered with or moved (US-7.6 AC4) */
    public Map<String, JsonNode> context(UUID runId) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        jdbc.sql("SELECT entry_key, content FROM run_context WHERE run_id = :run").param("run", runId)
                .query((ResultSet rs) -> {
                    String key = rs.getString("entry_key");
                    String stored = new String(rs.getBytes("content"), StandardCharsets.UTF_8);
                    out.put(key, read(cipher.decrypt(runId, key, stored)));
                });
        return out;
    }

    /** Rows per key id, for the rotation report at startup (US-7.6 AC3); {@code null} is plain. */
    public Map<String, Long> contextRowsByKid() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT key_id, COUNT(*) AS n FROM run_context GROUP BY key_id")
                .query((ResultSet rs) -> {
                    out.put(rs.getString("key_id"), rs.getLong("n"));
                });
        return out;
    }

    public Optional<Run> run(UUID id) {
        return jdbc.sql("SELECT * FROM pipeline_run WHERE id = :id").param("id", id).query(RunRows::mapRun).optional();
    }

    public List<Step> steps(UUID runId) {
        return jdbc.sql("SELECT * FROM step_execution WHERE run_id = :run").param("run", runId)
                .query(RunRows::mapStep).list();
    }

    /** {@code RUNNING} with the segment deadline from database time (US-5.1 enforces it). */
    public void markRunning(UUID runId, long runTimeoutMs) {
        jdbc.sql("""
                UPDATE pipeline_run SET status = 'RUNNING', started_at = SYSUTCDATETIME(),
                    segment_started_at = SYSUTCDATETIME(), deadline_at = DATEADD(MILLISECOND, :t, SYSUTCDATETIME())
                WHERE id = :id""").param("t", runTimeoutMs).param("id", runId).update();
    }

    public void markStepRunning(UUID runId, String stepId) {
        jdbc.sql("UPDATE step_execution SET status = 'RUNNING', started_at = SYSUTCDATETIME(), "
                + "row_version = row_version + 1 WHERE run_id = :run AND step_id = :step AND status = 'PENDING'")
                .param("run", runId).param("step", stepId).update();
    }

    public void finishStep(UUID runId, String stepId, StepStatus status, int attempts, Integer httpStatus,
            Long durationMs, String errorCode, String errorMessage, boolean fallbackApplied, Long completionSeq) {
        jdbc.sql("""
                UPDATE step_execution SET status = :status, attempts = :attempts, http_status = :http,
                    duration_ms = :duration, error_code = :code, error_message = :msg, fallback_applied = :fb,
                    completion_seq = :seq, ended_at = SYSUTCDATETIME(), row_version = row_version + 1
                WHERE run_id = :run AND step_id = :step""")
                .param("status", status.name()).param("attempts", attempts).param("http", httpStatus)
                .param("duration", durationMs).param("code", errorCode).param("msg", truncate(errorMessage))
                .param("fb", fallbackApplied ? 1 : 0).param("seq", completionSeq)
                .param("run", runId).param("step", stepId).update();
    }

    public void skipStep(UUID runId, String stepId, String reason) {
        jdbc.sql("UPDATE step_execution SET status = 'SKIPPED', skip_reason = :reason, ended_at = SYSUTCDATETIME(), "
                + "row_version = row_version + 1 WHERE run_id = :run AND step_id = :step AND status = 'PENDING'")
                .param("reason", reason).param("run", runId).param("step", stepId).update();
    }

    /** A pending step that will never start because the run stopped (README §7: {@code CANCELLED}). */
    public void cancelStep(UUID runId, String stepId) {
        jdbc.sql("UPDATE step_execution SET status = 'CANCELLED', ended_at = SYSUTCDATETIME(), "
                + "row_version = row_version + 1 WHERE run_id = :run AND step_id = :step AND status = 'PENDING'")
                .param("run", runId).param("step", stepId).update();
    }

    /** README §5.1 rule 7: stop intent is persisted with its cause. */
    public void stop(UUID runId, String reason, String errorCode, String errorMessage, String errorStepId) {
        jdbc.sql("""
                UPDATE pipeline_run SET stop_reason = COALESCE(stop_reason, :reason),
                    error_code = COALESCE(error_code, :code), error_message = COALESCE(error_message, :msg),
                    error_step_id = COALESCE(error_step_id, :step)
                WHERE id = :id""")
                .param("reason", reason).param("code", errorCode).param("msg", truncate(errorMessage))
                .param("step", errorStepId).param("id", runId).update();
    }

    /** Ends the run and gives up its lease. */
    public void finishRun(UUID runId, RunStatus status) {
        jdbc.sql("""
                UPDATE pipeline_run SET status = :status, completed_at = SYSUTCDATETIME(), ended_at = SYSUTCDATETIME(),
                    owner_instance = NULL, lease_until = NULL
                WHERE id = :id""").param("status", status.name()).param("id", runId).update();
    }

    /** One statement for every locally waiting run (US-4.1's shared poll). */
    public Map<UUID, RunStatus> statuses(Collection<UUID> ids) {
        Map<UUID, RunStatus> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, status FROM pipeline_run WHERE id IN (:ids)").param("ids", List.copyOf(ids))
                .query((ResultSet rs) -> {
                    out.put(UUID.fromString(rs.getString("id")), RunStatus.valueOf(rs.getString("status")));
                });
        return out;
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Context value is not serialisable", e);
        }
    }

    private JsonNode read(String content) {
        try {
            return json.readTree(content);
        } catch (IOException e) {
            throw new IllegalStateException("Stored context is not readable", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    static Run mapRun(ResultSet rs, int i) throws SQLException {
        return new Run(UUID.fromString(rs.getString("id")), rs.getString("pipeline_code"), rs.getInt("pipeline_version"),
                RunStatus.valueOf(rs.getString("status")), rs.getString("mode"), rs.getString("correlation_id"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), instant(rs, "ended_at"), rs.getString("stop_reason"),
                rs.getString("error_code"), rs.getString("error_message"), rs.getString("error_step_id"),
                rs.getString("owner_instance"), rs.getLong("lease_epoch"));
    }

    static Step mapStep(ResultSet rs, int i) throws SQLException {
        int http = rs.getInt("http_status");
        Integer httpStatus = rs.wasNull() ? null : http;
        long duration = rs.getLong("duration_ms");
        Long durationMs = rs.wasNull() ? null : duration;
        return new Step(rs.getString("step_id"), StepStatus.valueOf(rs.getString("status")), rs.getInt("exec_no"),
                rs.getInt("attempts"), httpStatus, rs.getBoolean("fallback_applied"), instant(rs, "started_at"),
                instant(rs, "ended_at"), durationMs, rs.getString("error_code"), rs.getString("error_message"),
                rs.getString("skip_reason"));
    }
}
