package com.crisil.apiorch.pipeline.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** A published version: the publish response (no {@code definition}) and {@code GET …/versions/{v}} (US-3.3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionResponse(String code, int version, String checksum, Instant publishedAt, String publishedBy,
        String comment, List<List<String>> executionPlan, JsonNode definition) {
}
