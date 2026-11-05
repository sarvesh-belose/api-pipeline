package com.crisil.apiorch.observability;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.config.ApiorchProperties;

/**
 * Inserts one complete {@code step_attempt} row (US-7.2), in the caller's fenced transaction, once per attempt and
 * never updated: that is what lets the application role hold {@code INSERT}/{@code SELECT} only (US-7.1 AC7).
 * Bodies are truncated to {@code apiorch.logging.default-max-body-bytes} with the truncated flags set; US-7.3 adds
 * capture levels and masking, applied <b>before</b> truncation.
 */
@Component
public class AttemptWriter {

    static final int DEFAULT_MAX_BODY_BYTES = 262_144;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final int maxBodyBytes;

    @Autowired
    public AttemptWriter(JdbcClient jdbc, ObjectMapper json, ApiorchProperties properties) {
        this(jdbc, json, DEFAULT_MAX_BODY_BYTES);
    }

    AttemptWriter(JdbcClient jdbc, ObjectMapper json, int maxBodyBytes) {
        this.jdbc = jdbc;
        this.json = json;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(AttemptRecord a) {
        UUID id = UUID.randomUUID();
        Truncated request = truncate(a.requestBody(), maxBodyBytes);
        Truncated response = truncate(a.responseBody(), maxBodyBytes);
        jdbc.sql("""
                INSERT INTO step_attempt (id, run_id, step_id, exec_no, attempt_no, kind, event_seq, endpoint_name, method,
                    url, request_headers, request_body, request_bytes, request_truncated, response_status,
                    response_headers, response_body, response_bytes, response_truncated, outcome, error_class,
                    error_message, circuit_state, next_retry_delay_ms, started_at, ended_at, duration_ms)
                VALUES (:id, :run, :step, :execNo, :attemptNo, :kind, :seq, :endpoint, :method, :url, :reqHeaders,
                    :reqBody, :reqBytes, :reqTrunc, :status, :resHeaders, :resBody, :resBytes, :resTrunc, :outcome,
                    :errorClass, :errorMessage, :circuit, :retryDelay, :started, :ended, :duration)""")
                .param("id", id).param("run", a.runId()).param("step", a.stepId()).param("execNo", a.execNo())
                .param("attemptNo", a.attemptNo()).param("kind", a.kind()).param("seq", a.eventSeq())
                .param("endpoint", a.endpointName()).param("method", a.method()).param("url", a.url())
                .param("reqHeaders", headers(a.requestHeaders())).param("reqBody", request.text())
                .param("reqBytes", a.requestBytes()).param("reqTrunc", request.truncated() ? 1 : 0)
                .param("status", a.responseStatus()).param("resHeaders", headers(a.responseHeaders()))
                .param("resBody", response.text()).param("resBytes", a.responseBytes())
                .param("resTrunc", response.truncated() ? 1 : 0).param("outcome", a.outcome())
                .param("errorClass", a.errorClass()).param("errorMessage", a.errorMessage())
                .param("circuit", a.circuitState()).param("retryDelay", a.nextRetryDelayMs())
                .param("started", Timestamp.valueOf(a.startedAt().atOffset(ZoneOffset.UTC).toLocalDateTime()))
                .param("ended", a.endedAt() == null ? null
                        : Timestamp.valueOf(a.endedAt().atOffset(ZoneOffset.UTC).toLocalDateTime()))
                .param("duration", a.durationMs())
                .update();
        return id;
    }

    record Truncated(String text, boolean truncated) {
    }

    /** Cuts {@code text} to at most {@code maxBytes} UTF-8 bytes without splitting a character. */
    static Truncated truncate(String text, int maxBytes) {
        if (text == null) {
            return new Truncated(null, false);
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new Truncated(text, false);
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new Truncated(new String(bytes, 0, end, StandardCharsets.UTF_8), true);
    }

    private String headers(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Headers are not serialisable", e);
        }
    }
}
