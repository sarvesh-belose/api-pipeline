package com.crisil.apiorch.pipeline.dto;

import jakarta.validation.constraints.Size;

/** {@code POST …/{code}/publish} (US-3.3). */
public record PublishRequest(@Size(max = 500) String comment) {
}
