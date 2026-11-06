package com.crisil.apiorch.run;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.ProblemTypes;
import com.crisil.apiorch.observability.RunExchangeWriter;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.run.dto.ExchangeView;
import com.crisil.apiorch.run.dto.RunResource;
import com.crisil.apiorch.security.Roles;

/**
 * Reads one run for {@code GET /api/v1/runs/{runId}} (US-4.2). A caller sees only the runs it created; operators see
 * all. A run the caller may not see is reported exactly like a missing one, so run IDs can't be probed (AC4). State
 * always comes from the database, so a poll on any node returns the latest committed state (AC6).
 */
@Service
public class RunQueryService {

    private final RunRows rows;
    private final RunResourceAssembler assembler;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RunQueryService(RunRows rows, RunResourceAssembler assembler, JdbcClient jdbc, ObjectMapper json) {
        this.rows = rows;
        this.assembler = assembler;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public RunResource get(String runId, Authentication caller) {
        UUID id = parse(runId).orElseThrow(() -> RunResourceAssembler.notFound(runId));
        RunRows.Run run = rows.run(id)
                .filter(r -> isOps(caller) || r.createdBy().equals(caller.getName()))
                .orElseThrow(() -> RunResourceAssembler.notFound(runId));
        return assembler.assemble(run);
    }

    /** US-7.7 AC6, for {@code apiorch.ops} (the security chain checks the role). */
    @Transactional(readOnly = true)
    public List<ExchangeView> exchanges(String runId, String kind) {
        if (!kind.equals(RunExchangeWriter.INBOUND_REQUEST) && !kind.equals(RunExchangeWriter.CALLER_RESPONSE)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "kind must be INBOUND_REQUEST or CALLER_RESPONSE");
        }
        UUID id = parse(runId).filter(u -> rows.run(u).isPresent())
                .orElseThrow(() -> RunResourceAssembler.notFound(runId));
        return jdbc.sql("""
                SELECT event_seq, kind, occurred_at, http_status, headers, body, bytes, truncated, final, payload_purged
                FROM run_exchange WHERE run_id = :run AND kind = :kind ORDER BY event_seq""")
                .param("run", id).param("kind", kind).query((rs, i) -> {
                    int status = rs.getInt("http_status");
                    Integer httpStatus = rs.wasNull() ? null : status;
                    long bytes = rs.getLong("bytes");
                    Long size = rs.wasNull() ? null : bytes;
                    String body = rs.getString("body");
                    JsonNode parsed = parseJson(body);
                    return new ExchangeView(rs.getLong("event_seq"), rs.getString("kind"),
                            rs.getTimestamp("occurred_at").toLocalDateTime().toInstant(ZoneOffset.UTC), httpStatus,
                            parseJson(rs.getString("headers")), parsed, parsed == null ? body : null, size,
                            rs.getBoolean("truncated"), rs.getBoolean("final"), rs.getBoolean("payload_purged"));
                }).list();
    }

    private JsonNode parseJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isOps(Authentication caller) {
        return caller.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + Roles.OPS));
    }

    private static Optional<UUID> parse(String runId) {
        try {
            UUID id = UUID.fromString(runId);
            return id.toString().equalsIgnoreCase(runId) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
