package com.crisil.apiorch.observability;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.definition.PipelineDefinition.Capture;

/**
 * Inserts one complete {@code step_attempt} row (US-7.2), in the caller's fenced transaction, once per attempt and
 * never updated: that is what lets the application role hold {@code INSERT}/{@code SELECT} only (US-7.1 AC7).
 * The capture level decides what is kept beyond the metadata (US-7.3): {@code METADATA} keeps no headers or bodies,
 * {@code HEADERS} adds masked headers, {@code FULL} adds masked bodies. Masking comes <b>before</b> truncation to
 * {@code maxBodyBytes}; the URL's query is masked at every level. With
 * {@code apiorch.logging.include-bodies-in-logs} and {@code FULL}, the masked bodies (at most 4 KB each) are also
 * logged.
 */
@Component
public class AttemptWriter {

    private static final Logger log = LoggerFactory.getLogger(AttemptWriter.class);
    static final int DEFAULT_MAX_BODY_BYTES = 262_144;
    static final int MAX_LOGGED_BODY_BYTES = 4096;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final int maxBodyBytes;
    private final MaskingPolicy globals;
    private final boolean bodiesInLogs;

    @Autowired
    public AttemptWriter(JdbcClient jdbc, ObjectMapper json, ApiorchProperties properties) {
        this(jdbc, json, properties.logging().defaultMaxBodyBytes(),
                MaskingPolicy.of(properties.logging().alwaysMaskHeaders(), properties.logging().alwaysMaskKeys()),
                properties.logging().includeBodiesInLogs());
    }

    AttemptWriter(JdbcClient jdbc, ObjectMapper json, int maxBodyBytes, MaskingPolicy globals, boolean bodiesInLogs) {
        this.jdbc = jdbc;
        this.json = json;
        this.maxBodyBytes = maxBodyBytes;
        this.globals = globals;
        this.bodiesInLogs = bodiesInLogs;
    }

    /** {@code FULL} at the default body limit, masked with the global policy only. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(AttemptRecord a) {
        return write(a, Capture.FULL, maxBodyBytes, globals);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(AttemptRecord a, Capture capture, int bodyLimit, MaskingPolicy policy) {
        UUID id = UUID.randomUUID();
        boolean withHeaders = capture != Capture.METADATA;
        boolean withBodies = capture == Capture.FULL;
        Truncated request = withBodies ? truncate(PayloadMasker.maskBody(a.requestBody(),
                contentType(a.requestHeaders()), a.requestBytes(), policy), bodyLimit) : new Truncated(null, false);
        Truncated response = withBodies ? truncate(PayloadMasker.maskBody(a.responseBody(),
                contentType(a.responseHeaders()), a.responseBytes(), policy), bodyLimit) : new Truncated(null, false);
        String url = PayloadMasker.maskUrl(a.url(), policy);
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
                .param("endpoint", a.endpointName()).param("method", a.method()).param("url", url)
                .param("reqHeaders", withHeaders ? headers(a.requestHeaders(), policy) : null)
                .param("reqBody", request.text())
                .param("reqBytes", a.requestBytes()).param("reqTrunc", request.truncated() ? 1 : 0)
                .param("status", a.responseStatus())
                .param("resHeaders", withHeaders ? headers(a.responseHeaders(), policy) : null)
                .param("resBody", response.text()).param("resBytes", a.responseBytes())
                .param("resTrunc", response.truncated() ? 1 : 0).param("outcome", a.outcome())
                .param("errorClass", a.errorClass()).param("errorMessage", truncateChars(a.errorMessage(), 1000))
                .param("circuit", a.circuitState()).param("retryDelay", a.nextRetryDelayMs())
                .param("started", Timestamp.valueOf(a.startedAt().atOffset(ZoneOffset.UTC).toLocalDateTime()))
                .param("ended", a.endedAt() == null ? null
                        : Timestamp.valueOf(a.endedAt().atOffset(ZoneOffset.UTC).toLocalDateTime()))
                .param("duration", a.durationMs())
                .update();
        if (bodiesInLogs && withBodies) {
            log.info("attempt bodies step={} attempt={} url={} request={} response={}", a.stepId(), a.attemptNo(), url,
                    truncate(request.text(), MAX_LOGGED_BODY_BYTES).text(),
                    truncate(response.text(), MAX_LOGGED_BODY_BYTES).text());
        }
        return id;
    }

    private static String contentType(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
                .map(Map.Entry::getValue).findFirst().orElse(null);
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

    private static String truncateChars(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private String headers(Map<String, String> headers, MaskingPolicy policy) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        Map<String, String> masked = PayloadMasker.maskHeaders(headers, policy);
        try {
            return json.writeValueAsString(masked);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Headers are not serialisable", e);
        }
    }
}
