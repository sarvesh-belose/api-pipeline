package com.crisil.apiorch.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * The result of a step execution (US-4.3, US-6.1). {@code body} and {@code headers} become
 * {@code steps.<id>.body} and {@code steps.<id>.headers} in the run context.
 *
 * @param errorCode a step error code ({@code HTTP_ERROR}, {@code TIMEOUT}, {@code CONNECT_ERROR},
 *                  {@code SUCCESS_CHECK_FAILED}, {@code EXPRESSION_ERROR}, {@code ENDPOINT_DISABLED},
 *                  {@code CIRCUIT_OPEN}, {@code RESPONSE_TOO_LARGE}, …) when {@code status} is {@link Status#FAILED}
 */
public record StepOutcome(
        Status status,
        Integer httpStatus,
        JsonNode headers,
        JsonNode body,
        int attempts,
        long durationMs,
        String errorCode,
        String errorMessage) {

    public enum Status {
        SUCCEEDED,
        FAILED,
        /** An async step's request was accepted; completion arrives by callback (US-6.1). */
        ASYNC_ACCEPTED
    }

    public static StepOutcome succeeded(int httpStatus, JsonNode headers, JsonNode body, int attempts, long durationMs) {
        return new StepOutcome(Status.SUCCEEDED, httpStatus, headers, body, attempts, durationMs, null, null);
    }

    public static StepOutcome failed(Integer httpStatus, String errorCode, String errorMessage, int attempts,
            long durationMs) {
        return new StepOutcome(Status.FAILED, httpStatus, MissingNode.getInstance(), MissingNode.getInstance(),
                attempts, durationMs, errorCode, errorMessage);
    }
}
