package com.crisil.apiorch.run.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** One {@code run_exchange} row as {@code GET /api/v1/runs/{runId}/exchanges/{kind}} returns it (US-7.7 AC6).
 * {@code body} is the masked JSON; a placeholder or a truncated body is returned as {@code bodyText}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeView(long seq, String kind, Instant at, Integer httpStatus, JsonNode headers, JsonNode body,
        String bodyText, Long bytes, boolean truncated, @JsonProperty("final") boolean last, boolean payloadPurged) {
}
