package com.crisil.apiorch.run.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** {@code GET /api/v1/runs/{runId}/timeline} (US-7.4): what a run exchanged, in {@code seq} order, without bodies. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Timeline(RunSummary run, List<StepSummary> steps, List<Call> calls, List<Event> events, Totals totals) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunSummary(UUID runId, String pipeline, int version, String status, String correlationId,
            Instant createdAt, Instant startedAt, Instant endedAt, int resumeCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepSummary(String stepId, int execNo, String status, int attempts, Long durationMs,
            Integer httpStatus, String skipReason, boolean fallbackApplied, String compensationStatus) {
    }

    /**
     * One entry of {@code calls[]}: {@code INBOUND_REQUEST} and {@code CALLER_RESPONSE} from {@code run_exchange},
     * {@code REQUEST} and {@code COMPENSATION} from {@code step_attempt}; {@code CALLBACK} arrives with US-6.2.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Call(Long seq, String type, Instant at, String stepId, Integer execNo, Integer attempt,
            String endpoint, String method, String url, Instant startedAt, Long durationMs, Long requestBytes,
            Long responseBytes, Integer httpStatus, String outcome, String circuitState, Long bytes,
            @JsonProperty("final") Boolean last) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(long seq, String type, String stepId, Integer execNo, Integer attempt, Instant at,
            JsonNode details, String actor) {
    }

    public record Totals(int calls, long requestBytes, long responseBytes, long downstreamTimeMs, Long wallTimeMs) {
    }

    /** {@code GET …/steps/{stepId}/attempts/{n}} (US-7.4 AC4), masked as stored. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttemptDetail(String stepId, int execNo, int attempt, String kind, String endpoint, String method,
            String url, Instant startedAt, Long durationMs, Integer httpStatus, String outcome, String errorMessage,
            JsonNode requestHeaders, Object requestBody, boolean requestTruncated, JsonNode responseHeaders,
            Object responseBody, boolean responseTruncated, Long requestBytes, Long responseBytes,
            boolean payloadPurged) {
    }

    /** One run in {@code GET /api/v1/runs} (US-7.4 AC6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunSummaryRow(UUID runId, String pipeline, int version, String status, String correlationId,
            Instant createdAt, Long durationMs, String createdBy, String errorCode) {
    }

    public record Page(List<RunSummaryRow> content, int page, int size, long totalElements, int totalPages) {
    }
}
