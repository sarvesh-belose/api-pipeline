package com.crisil.apiorch.observability;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One HTTP attempt of a step, complete, as {@link AttemptWriter} stores it in {@code step_attempt} (US-7.2). Bodies
 * are the text as sent and received; sizes are the true byte counts even when the stored body is truncated.
 *
 * @param outcome one of README §10's attempt outcomes ({@code SUCCESS}, {@code HTTP_ERROR}, {@code TIMEOUT}, …)
 */
public record AttemptRecord(
        UUID runId,
        String stepId,
        int execNo,
        int attemptNo,
        String kind,
        long eventSeq,
        String endpointName,
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestBody,
        long requestBytes,
        Integer responseStatus,
        Map<String, String> responseHeaders,
        String responseBody,
        long responseBytes,
        String outcome,
        String errorClass,
        String errorMessage,
        String circuitState,
        Long nextRetryDelayMs,
        Instant startedAt,
        Instant endedAt,
        long durationMs) {
}
