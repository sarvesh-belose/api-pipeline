package com.crisil.apiorch.run.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** The run resource shared by US-4.1, US-4.2, US-6.5 and US-7.4. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResource(
        UUID runId,
        String pipeline,
        int version,
        String status,
        String mode,
        String correlationId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant endedAt,
        Long durationMs,
        JsonNode output,
        boolean outputFinal,
        boolean outputPurged,
        List<String> pendingAsyncSteps,
        List<Error> errors,
        List<Step> steps,
        Map<String, String> links) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    /** {@code fallbackApplied} is {@code true} for a fallback value, otherwise left out (US-4.6). */
    public record Error(String stepId, String code, String message, Integer httpStatus, Boolean fallbackApplied) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(String id, String status, int attempts, Integer httpStatus, Long durationMs,
            boolean fallbackApplied, String skipReason) {
    }
}
