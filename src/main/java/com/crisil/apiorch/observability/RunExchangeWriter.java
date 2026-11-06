package com.crisil.apiorch.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Inserts one {@code run_exchange} row (US-7.7): the caller's request ({@code INBOUND_REQUEST}) or one of apiorch's
 * responses ({@code CALLER_RESPONSE}), with its event ({@code INBOUND_REQUEST_RECEIVED} or
 * {@code CALLER_RESPONSE_SENT}) and that event's {@code seq}. It runs in the caller's transaction, which holds the
 * run lock. Capture and masking follow US-7.3, masking before truncation: {@code METADATA} keeps status and bytes,
 * {@code HEADERS} adds masked headers, {@code FULL} adds the masked body. Rows are inserted once, never updated.
 */
@Component
public class RunExchangeWriter {

    public static final String INBOUND_REQUEST = "INBOUND_REQUEST";
    public static final String CALLER_RESPONSE = "CALLER_RESPONSE";

    /** One exchange as it went over the wire; {@code body} is the real text and {@code bytes} its true size. */
    public record Exchange(String kind, Integer httpStatus, Map<String, String> headers, String body,
            String contentType, long bytes, boolean last) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ActivityRecorder recorder;

    public RunExchangeWriter(JdbcClient jdbc, ObjectMapper json, ActivityRecorder recorder) {
        this.jdbc = jdbc;
        this.json = json;
        this.recorder = recorder;
    }

    /** Records the event and inserts the row; {@code details} are extra catalog keys (for example {@code replay}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public long write(UUID runId, Exchange e, CaptureSettings settings, Map<String, ?> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (e.httpStatus() != null) {
            event.put("httpStatus", e.httpStatus());
        }
        event.put("bytes", e.bytes());
        if (e.kind().equals(CALLER_RESPONSE)) {
            event.put("final", e.last());
        }
        if (details != null) {
            event.putAll(details);
        }
        long seq = recorder.record(runId, e.kind().equals(INBOUND_REQUEST) ? EventType.INBOUND_REQUEST_RECEIVED
                : EventType.CALLER_RESPONSE_SENT, null, event, null);
        AttemptWriter.Truncated body = settings.bodies() ? AttemptWriter.truncate(
                PayloadMasker.maskBody(e.body(), e.contentType(), e.bytes(), settings.policy()), settings.maxBodyBytes())
                : new AttemptWriter.Truncated(null, false);
        jdbc.sql("""
                INSERT INTO run_exchange (id, run_id, kind, event_seq, http_status, headers, body, bytes, truncated,
                    final, occurred_at)
                VALUES (:id, :run, :kind, :seq, :status, :headers, :body, :bytes, :truncated, :final, SYSUTCDATETIME())""")
                .param("id", UUID.randomUUID()).param("run", runId).param("kind", e.kind()).param("seq", seq)
                .param("status", e.httpStatus())
                .param("headers", settings.headers() ? headers(e.headers(), settings.policy()) : null)
                .param("body", body.text()).param("bytes", e.bytes()).param("truncated", body.truncated() ? 1 : 0)
                .param("final", e.last() ? 1 : 0).update();
        return seq;
    }

    /** Whether the run already has its {@code final = 1} response row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasFinal(UUID runId) {
        return jdbc.sql("SELECT COUNT(*) FROM run_exchange WHERE run_id = :run AND kind = 'CALLER_RESPONSE' AND final = 1")
                .param("run", runId).query(Long.class).single() > 0;
    }

    private String headers(Map<String, String> headers, MaskingPolicy policy) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(PayloadMasker.maskHeaders(headers, policy));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Headers are not serialisable", ex);
        }
    }
}
