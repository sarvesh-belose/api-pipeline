package com.crisil.apiorch.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.observability.ActivityRecorder;
import com.crisil.apiorch.observability.ActivityRecorder.StepRef;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.EventType;
import com.crisil.apiorch.observability.ProblemTypes;
import com.crisil.apiorch.persistence.JdbcRunLeaseService;
import com.crisil.apiorch.run.dto.Timeline;
import com.crisil.apiorch.security.Roles;

/**
 * The run's timeline, attempt detail and run search (US-7.4). The timeline is a fixed number of statements whatever
 * the run's size (AC8): the run first, then steps, attempts, exchanges and optionally events, each bounded by the
 * run's lifetime (widened by a day) on the table's partitioning column, so SQL Server eliminates the other month
 * partitions instead of scanning them. Calls are merged by their stored {@code event_seq}. A caller sees only its own
 * runs; detail endpoints ({@code apiorch.ops}) record {@code PAYLOAD_ACCESSED} before the body is returned (AC9).
 */
@Service
public class TimelineService {

    static final int MAX_PAGE_SIZE = 100;

    /**
     * The partitioned reads, each bounded on its table's partitioning column ({@code :from}/{@code :to} from the
     * run's lifetime) plus the dialect's explicit partition range, so SQL Server reads only the partitions the run
     * can be in (checked by {@code TimelinePartitionEliminationIT}).
     */
    String attemptsSql() {
        return """
                SELECT event_seq, kind, step_id, exec_no, attempt_no, endpoint_name, method, url, started_at,
                       duration_ms, request_bytes, response_bytes, response_status, outcome, circuit_state
                FROM step_attempt WHERE run_id = :id AND started_at >= :from AND started_at < :to"""
                + dialect.partitionRange("started_at");
    }

    String exchangesSql() {
        return """
                SELECT event_seq, kind, occurred_at, http_status, bytes, final FROM run_exchange
                WHERE run_id = :id AND occurred_at >= :from AND occurred_at < :to"""
                + dialect.partitionRange("occurred_at");
    }

    String eventsSql() {
        return """
                SELECT seq, event_type, step_id, exec_no, attempt, occurred_at, details, actor FROM run_event
                WHERE run_id = :id AND occurred_at >= :from AND occurred_at < :to"""
                + dialect.partitionRange("occurred_at") + " ORDER BY seq";
    }

    private record Window(UUID id, Instant from, Instant to) {
    }

    private final JdbcClient jdbc;
    private final com.crisil.apiorch.persistence.SqlDialect dialect;
    private final ObjectMapper json;
    private final ActivityRecorder recorder;
    private final JdbcRunLeaseService leases;
    private final TransactionTemplate tx;
    private final java.time.Clock clock;

    public TimelineService(JdbcClient jdbc, ObjectMapper json, ActivityRecorder recorder, JdbcRunLeaseService leases,
            TransactionTemplate tx, java.time.Clock clock, com.crisil.apiorch.persistence.SqlDialect dialect) {
        this.dialect = dialect;
        this.clock = clock;
        this.jdbc = jdbc;
        this.json = json;
        this.recorder = recorder;
        this.leases = leases;
        this.tx = tx;
    }

    @Transactional(readOnly = true)
    public Timeline timeline(String runId, boolean withEvents, Authentication caller) {
        UUID id = runIdOf(runId);
        Map<String, Object> run = jdbc.sql("""
                SELECT id, pipeline_code, pipeline_version, status, correlation_id, created_by, created_at, started_at,
                       ended_at, resume_count FROM pipeline_run WHERE id = :id""")
                .param("id", id).query().listOfRows().stream().findFirst()
                .filter(r -> isOps(caller) || caller.getName().equals(r.get("created_by")))
                .orElseThrow(() -> RunResourceAssembler.notFound(runId));
        Window w = window(id, instant(run.get("created_at")), instant(run.get("ended_at")));
        Timeline.RunSummary summary = new Timeline.RunSummary(id, (String) run.get("pipeline_code"),
                ((Number) run.get("pipeline_version")).intValue(), (String) run.get("status"),
                (String) run.get("correlation_id"), instant(run.get("created_at")), instant(run.get("started_at")),
                instant(run.get("ended_at")), ((Number) run.get("resume_count")).intValue());

        List<Timeline.StepSummary> steps = jdbc.sql("""
                SELECT step_id, exec_no, status, attempts, duration_ms, http_status, skip_reason, fallback_applied,
                       compensation_status FROM step_execution WHERE run_id = :id""")
                .param("id", id).query((rs, i) -> new Timeline.StepSummary(rs.getString("step_id"), rs.getInt("exec_no"),
                        rs.getString("status"), rs.getInt("attempts"), longOrNull(rs, "duration_ms"),
                        intOrNull(rs, "http_status"), rs.getString("skip_reason"), rs.getBoolean("fallback_applied"),
                        rs.getString("compensation_status") != null ? rs.getString("compensation_status")
                                : "NOT_REQUIRED")).list();

        List<Timeline.Call> calls = new ArrayList<>();
        jdbc.sql(attemptsSql())
                .param("id", id).param("from", ts(w.from())).param("to", ts(w.to()))
                .query((ResultSet rs) -> {
                    calls.add(new Timeline.Call(longOrNull(rs, "event_seq"), rs.getString("kind"), null,
                            rs.getString("step_id"), rs.getInt("exec_no"), rs.getInt("attempt_no"),
                            rs.getString("endpoint_name"), rs.getString("method"), rs.getString("url"),
                            instant(rs.getTimestamp("started_at")), longOrNull(rs, "duration_ms"),
                            longOrNull(rs, "request_bytes"), longOrNull(rs, "response_bytes"),
                            intOrNull(rs, "response_status"), rs.getString("outcome"), rs.getString("circuit_state"),
                            null, null));
                });
        jdbc.sql(exchangesSql())
                .param("id", id).param("from", ts(w.from())).param("to", ts(w.to()))
                .query((ResultSet rs) -> {
                    boolean response = rs.getString("kind").equals("CALLER_RESPONSE");
                    calls.add(new Timeline.Call(longOrNull(rs, "event_seq"), rs.getString("kind"),
                            instant(rs.getTimestamp("occurred_at")), null, null, null, null, null, null, null, null,
                            null, null, intOrNull(rs, "http_status"), null, null, longOrNull(rs, "bytes"),
                            response ? rs.getBoolean("final") : null));
                });
        calls.sort(Comparator.comparing(c -> c.seq() == null ? Long.MAX_VALUE : c.seq()));

        List<Timeline.Event> events = null;
        if (withEvents) {
            events = jdbc.sql(eventsSql())
                    .param("id", id).param("from", ts(w.from())).param("to", ts(w.to()))
                    .query((rs, i) -> new Timeline.Event(rs.getLong("seq"), rs.getString("event_type"),
                            rs.getString("step_id"), intOrNull(rs, "exec_no"), intOrNull(rs, "attempt"),
                            instant(rs.getTimestamp("occurred_at")), parse(rs.getString("details")),
                            rs.getString("actor"))).list();
        }

        long requestBytes = 0;
        long responseBytes = 0;
        long downstream = 0;
        for (Timeline.Call c : calls) {
            if (c.endpoint() != null) {
                requestBytes += c.requestBytes() == null ? 0 : c.requestBytes();
                responseBytes += c.responseBytes() == null ? 0 : c.responseBytes();
                downstream += c.durationMs() == null ? 0 : c.durationMs();
            }
        }
        Long wall = summary.endedAt() == null ? null
                : java.time.Duration.between(summary.createdAt(), summary.endedAt()).toMillis();
        return new Timeline(summary, steps, calls, events,
                new Timeline.Totals(calls.size(), requestBytes, responseBytes, downstream, wall));
    }

    /** AC4, AC9: one attempt's masked detail for {@code apiorch.ops}; the read is recorded first. */
    public Timeline.AttemptDetail attempt(String runId, String stepId, int attemptNo, String kind, Authentication caller) {
        if (!kind.equals("REQUEST") && !kind.equals("COMPENSATION")) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "kind must be REQUEST or COMPENSATION");
        }
        UUID id = runIdOf(runId);
        Optional<Map<String, Object>> row = jdbc.sql("""
                SELECT * FROM step_attempt WHERE run_id = :id AND step_id = :step AND attempt_no = :n AND kind = :kind""")
                .param("id", id).param("step", stepId).param("n", attemptNo).param("kind", kind)
                .query().listOfRows().stream().findFirst();
        Map<String, Object> a = row.orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "attempt-not-found", "Run " + runId + " has no " + kind + " attempt " + attemptNo + " of " + stepId + "."));
        boolean purged = Boolean.TRUE.equals(bool(a.get("payload_purged")));
        Timeline.AttemptDetail detail = new Timeline.AttemptDetail(stepId, ((Number) a.get("exec_no")).intValue(),
                attemptNo, kind, (String) a.get("endpoint_name"), (String) a.get("method"), (String) a.get("url"),
                instant(a.get("started_at")), number(a.get("duration_ms")), a.get("response_status") == null ? null
                        : ((Number) a.get("response_status")).intValue(), (String) a.get("outcome"),
                (String) a.get("error_message"), purged ? null : parse((String) a.get("request_headers")),
                purged ? null : body((String) a.get("request_body")), Boolean.TRUE.equals(bool(a.get("request_truncated"))),
                purged ? null : parse((String) a.get("response_headers")),
                purged ? null : body((String) a.get("response_body")),
                Boolean.TRUE.equals(bool(a.get("response_truncated"))), number(a.get("request_bytes")),
                number(a.get("response_bytes")), purged);
        recordAccess(id, "ATTEMPT", stepId, detail.execNo(), attemptNo, caller);
        return detail;
    }

    /** AC9: who read a payload, in its own short transaction under the run lock, before the data is returned. */
    public void recordAccess(UUID runId, String kind, String stepId, Integer execNo, Integer attemptNo,
            Authentication caller) {
        tx.executeWithoutResult(s -> {
            leases.lockUnowned(runId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", kind);
            if (stepId != null) {
                details.put("stepId", stepId);
                details.put("execNo", execNo);
                details.put("attemptNo", attemptNo);
            }
            recorder.record(runId, EventType.PAYLOAD_ACCESSED, stepId == null ? null
                    : StepRef.attempt(stepId, execNo == null ? 1 : execNo, attemptNo == null ? 1 : attemptNo), details,
                    caller.getName());
        });
    }

    /** AC6, AC7: newest first; a caller with only {@code apiorch.run} sees its own runs whatever the filters. */
    @Transactional(readOnly = true)
    public Timeline.Page search(String pipeline, List<String> statuses, Instant from, Instant to, String correlationId,
            String createdBy, int page, int size, Authentication caller) {
        if (size < 1 || size > MAX_PAGE_SIZE || page < 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE);
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (pipeline != null) {
            where.append(" AND pipeline_code = :pipeline");
            params.put("pipeline", pipeline);
        }
        if (statuses != null && !statuses.isEmpty()) {
            where.append(" AND status IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (from != null) {
            where.append(" AND created_at >= :from");
            params.put("from", ts(from));
        }
        if (to != null) {
            where.append(" AND created_at < :to");
            params.put("to", ts(to));
        }
        if (correlationId != null) {
            where.append(" AND correlation_id = :cid");
            params.put("cid", correlationId);
        }
        String owner = isOps(caller) ? createdBy : caller.getName();
        if (owner != null) {
            where.append(" AND created_by = :by");
            params.put("by", owner);
        }
        long total = bind(jdbc.sql("SELECT COUNT(*) FROM pipeline_run" + where), params).query(Long.class).single();
        List<Timeline.RunSummaryRow> content = bind(jdbc.sql("""
                SELECT id, pipeline_code, pipeline_version, status, correlation_id, created_at, started_at, ended_at,
                       created_by, error_code FROM pipeline_run""" + where
                + " ORDER BY created_at DESC, id OFFSET " + ((long) page * size) + " ROWS FETCH NEXT " + size
                + " ROWS ONLY"), params)
                .query((rs, i) -> {
                    Instant started = instant(rs.getTimestamp("started_at"));
                    Instant ended = instant(rs.getTimestamp("ended_at"));
                    return new Timeline.RunSummaryRow(UUID.fromString(rs.getString("id")), rs.getString("pipeline_code"),
                            rs.getInt("pipeline_version"), rs.getString("status"), rs.getString("correlation_id"),
                            instant(rs.getTimestamp("created_at")), started != null && ended != null
                                    ? java.time.Duration.between(started, ended).toMillis() : null,
                            rs.getString("created_by"), rs.getString("error_code"));
                }).list();
        return new Timeline.Page(content, page, size, total, (int) ((total + size - 1) / size));
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        params.forEach(spec::param);
        return spec;
    }

    private Window window(UUID id, Instant createdAt, Instant endedAt) {
        Instant end = endedAt != null ? endedAt : Instant.now(clock);
        return new Window(id, createdAt.minus(1, ChronoUnit.DAYS), end.plus(1, ChronoUnit.DAYS));
    }

    private JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** A stored body: JSON when it parses, else the text (a placeholder or a truncated body). */
    private Object body(String text) {
        JsonNode node = parse(text);
        return node != null ? node : text;
    }

    private static UUID runIdOf(String runId) {
        try {
            UUID id = UUID.fromString(runId);
            if (id.toString().equalsIgnoreCase(runId)) {
                return id;
            }
        } catch (IllegalArgumentException e) {
            // reported as not found below
        }
        throw RunResourceAssembler.notFound(runId);
    }

    static boolean isOps(Authentication caller) {
        return caller.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + Roles.OPS));
    }

    private static Object ts(Instant i) {
        return bound(i);
    }

    /**
     * A time bound as a {@code LocalDateTime} (UTC), which mssql-jdbc sends as {@code datetime2}. A
     * {@code java.sql.Timestamp} goes as {@code datetime}, and SQL Server then reads every partition instead of
     * eliminating them (found by {@code TimelinePartitionEliminationIT}).
     */
    static java.time.LocalDateTime bound(Instant i) {
        return i.atOffset(ZoneOffset.UTC).toLocalDateTime();
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) {
            return t.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof java.time.LocalDateTime l) {
            return l.toInstant(ZoneOffset.UTC);
        }
        return null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue() != 0 : null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
