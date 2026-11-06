package com.crisil.apiorch.run;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.definition.PipelineDefinition;
import com.crisil.apiorch.definition.PipelineDefinition.Capture;
import com.crisil.apiorch.http.EndpointClientRegistry;
import com.crisil.apiorch.observability.CaptureSettings;
import com.crisil.apiorch.observability.MaskingPolicy;

/**
 * Resolves {@link CaptureSettings} (US-7.3): the step's {@code logging}, else the pipeline's, else the global
 * defaults. Masking always adds the global header and key lists; a step adds its endpoint's API key header.
 */
@Component
public class CapturePolicies {

    private final ApiorchProperties.Logging logging;
    private final EndpointClientRegistry endpoints;

    public CapturePolicies(ApiorchProperties properties, EndpointClientRegistry endpoints) {
        this.logging = properties.logging();
        this.endpoints = endpoints;
    }

    /** For the caller exchange (US-7.7): the pipeline's settings over the globals. */
    public CaptureSettings forPipeline(PipelineDefinition definition) {
        return resolve(definition.logging(), null, List.of());
    }

    /** For a step's attempts (US-7.2). */
    public CaptureSettings forStep(PipelineDefinition definition, CompiledStep step) {
        List<String> extraHeaders = new ArrayList<>();
        endpoints.get(step.definition().endpoint()).map(r -> r.config().auth())
                .filter(auth -> auth != null && auth.headerName() != null)
                .ifPresent(auth -> extraHeaders.add(auth.headerName()));
        return resolve(definition.logging(), step.definition().logging(), extraHeaders);
    }

    private CaptureSettings resolve(PipelineDefinition.LoggingSpec pipeline, PipelineDefinition.LoggingSpec step,
            List<String> extraHeaders) {
        Capture capture = step != null && step.capture() != null ? step.capture()
                : pipeline != null && pipeline.capture() != null ? pipeline.capture()
                : logging.defaultCapture();
        int maxBody = step != null && step.maxBodyBytes() != null ? step.maxBodyBytes()
                : pipeline != null && pipeline.maxBodyBytes() != null ? pipeline.maxBodyBytes()
                : logging.defaultMaxBodyBytes();
        List<String> headers = new ArrayList<>(logging.alwaysMaskHeaders());
        headers.addAll(extraHeaders);
        List<String> keys = new ArrayList<>(logging.alwaysMaskKeys());
        if (pipeline != null && pipeline.maskKeys() != null) {
            keys.addAll(pipeline.maskKeys());
        }
        if (step != null && step.maskKeys() != null) {
            keys.addAll(step.maskKeys());
        }
        return new CaptureSettings(capture, maxBody, MaskingPolicy.of(headers, keys));
    }
}
