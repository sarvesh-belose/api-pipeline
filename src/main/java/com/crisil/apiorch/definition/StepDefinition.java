package com.crisil.apiorch.definition;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.crisil.apiorch.definition.PipelineDefinition.LoggingSpec;
import com.crisil.apiorch.definition.PipelineDefinition.RetrySpec;

/** One step of a pipeline (US-3.1 step-level reference). Expression fields are JSONata strings. */
public record StepDefinition(
        @NotBlank @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN) String id,
        @Size(max = 500) String description,
        @NotBlank String endpoint,
        @NotNull Method method,
        @NotBlank @Pattern(regexp = "^/.*", message = "must start with /") String path,
        Map<String, String> query,
        Map<String, String> headers,
        String body,
        Mode mode,
        List<@NotBlank String> dependsOn,
        String when,
        String successWhen,
        @Min(100) @Max(120000) Integer timeoutMs,
        @Valid RetrySpec retry,
        Boolean idempotent,
        OnFailure onFailure,
        String fallback,
        @Valid CompensateSpec compensate,
        @Valid CallbackSpec callback,
        Boolean blocksRunCompletion,
        @Valid LoggingSpec logging) {

    public static final String ID_PATTERN = "^[a-z][a-z0-9_]{0,63}$";

    public enum Method { GET, POST, PUT, PATCH, DELETE }

    public enum Mode { SYNC, ASYNC_CALLBACK }

    public enum OnFailure { FAIL_RUN, CONTINUE, FALLBACK }

    public Mode effectiveMode() {
        return mode != null ? mode : Mode.SYNC;
    }

    public OnFailure effectiveOnFailure() {
        return onFailure != null ? onFailure : OnFailure.FAIL_RUN;
    }

    /** {@code true} for GET, PUT and DELETE unless declared (US-5.2). */
    public boolean effectiveIdempotent() {
        return idempotent != null ? idempotent : method != Method.POST && method != Method.PATCH;
    }

    public boolean effectiveBlocksRunCompletion() {
        return blocksRunCompletion == null || blocksRunCompletion;
    }

    /** The optional per-step compensation call (US-5.6). Omitting {@code endpoint} means the step's own. */
    public record CompensateSpec(
            String endpoint,
            @NotNull Method method,
            @NotBlank @Pattern(regexp = "^/.*", message = "must start with /") String path,
            Map<String, String> query,
            Map<String, String> headers,
            String body) {
    }

    /** Async completion settings (US-6.1, US-6.4). */
    public record CallbackSpec(@Min(1000) @Max(604800000) Integer timeoutMs, String successWhen) {
    }
}
