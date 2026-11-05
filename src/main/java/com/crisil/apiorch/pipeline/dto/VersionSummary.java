package com.crisil.apiorch.pipeline.dto;

import java.time.Instant;

/** One row of {@code GET …/{code}/versions} (US-3.3 AC6). */
public record VersionSummary(int version, String checksum, Instant publishedAt, String publishedBy, String comment,
        Integer sourceVersion, String sourceChecksum) {
}
