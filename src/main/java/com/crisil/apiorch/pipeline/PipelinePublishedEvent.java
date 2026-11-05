package com.crisil.apiorch.pipeline;

/** Raised after a publish commits (US-3.3), so this node stops serving a cached older latest version at once. */
public record PipelinePublishedEvent(String code, int version) {
}
