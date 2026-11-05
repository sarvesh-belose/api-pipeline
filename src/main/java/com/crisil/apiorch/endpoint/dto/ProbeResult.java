package com.crisil.apiorch.endpoint.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The result of {@code POST /api/v1/admin/endpoints/{name}/probe} (US-2.1 AC6). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProbeResult(boolean reachable, Integer httpStatus, long durationMs, String errorClass) {
}
