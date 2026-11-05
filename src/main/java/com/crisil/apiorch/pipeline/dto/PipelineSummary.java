package com.crisil.apiorch.pipeline.dto;

import java.time.Instant;

/** One row of {@code GET /api/v1/admin/pipelines} (US-3.1 AC5). */
public record PipelineSummary(
        String code,
        String description,
        boolean enabled,
        Integer latestVersion,
        Instant latestPublishedAt,
        boolean hasDraft,
        Instant draftUpdatedAt) {
}
