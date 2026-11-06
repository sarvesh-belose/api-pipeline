package com.crisil.apiorch.observability;

import com.crisil.apiorch.definition.PipelineDefinition.Capture;

/** What a log row keeps (US-7.3): the capture level, the body limit and the masking policy, already resolved. */
public record CaptureSettings(Capture capture, int maxBodyBytes, MaskingPolicy policy) {

    public boolean headers() {
        return capture != Capture.METADATA;
    }

    public boolean bodies() {
        return capture == Capture.FULL;
    }
}
