package com.crisil.apiorch.pipeline.dto;

import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code POST /api/v1/admin/pipelines} takes {@code code}, {@code description} and {@code definition};
 * {@code PUT …/{code}/draft} takes the same without {@code code} (US-3.1). The definition is kept as a tree so it
 * can be stored exactly as sent and bound strictly by the DSL mapper.
 */
public record DraftRequest(String code, @Size(max = 500) String description, JsonNode definition) {
}
