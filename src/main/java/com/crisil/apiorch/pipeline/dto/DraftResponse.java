package com.crisil.apiorch.pipeline.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.definition.ValidationReport;

/** A saved draft (US-3.1 AC1, AC4, AC5). {@code definition} is returned on GET; {@code validation} on save. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DraftResponse(
        String code,
        int version,
        String status,
        long rowVersion,
        Instant updatedAt,
        String updatedBy,
        JsonNode definition,
        ValidationReport validation) {
}
