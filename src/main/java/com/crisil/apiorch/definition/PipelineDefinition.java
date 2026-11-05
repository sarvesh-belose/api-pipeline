package com.crisil.apiorch.definition;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One pipeline document in the apiorch DSL (US-3.1, README §8). Structure only: the semantic rules (references,
 * cycles, endpoints, feature gates) are {@link DefinitionValidator}'s job. A {@code null} list or object means the
 * field was omitted, which matters for {@code dependsOn}: omitted means "the previous step", {@code []} means a
 * root step.
 */
public record PipelineDefinition(
        @Min(1) Integer dslVersion,
        @NotBlank @Pattern(regexp = CODE_PATTERN, message = "must match " + CODE_PATTERN) String code,
        @Size(max = 500) String description,
        List<@NotBlank String> allowedCallers,
        @Valid InputSpec input,
        @Min(1000) @Max(3600000) Integer runTimeoutMs,
        @Min(0) Integer syncWaitMs,
        @Valid LoggingSpec logging,
        @Valid DefaultsSpec defaults,
        @NotEmpty List<@NotNull @Valid StepDefinition> steps,
        String output) {

    public static final String CODE_PATTERN = "^[A-Z][A-Z0-9_]{2,63}$";

    /** The revision this document declares; absent means 1 (AC8). */
    public int effectiveDslVersion() {
        return dslVersion != null ? dslVersion : 1;
    }

    public record InputSpec(List<@NotBlank String> required, String validWhen) {
    }

    public enum Capture { METADATA, HEADERS, FULL }

    public record LoggingSpec(Capture capture, @Min(0) @Max(5242880) Integer maxBodyBytes, List<@NotBlank String> maskKeys) {
    }

    public enum Backoff { FIXED, EXPONENTIAL_JITTER }

    public record RetrySpec(
            @Min(1) @Max(10) Integer maxAttempts,
            Backoff backoff,
            @Min(10) @Max(60000) Integer initialMs,
            @Min(10) @Max(60000) Integer maxMs,
            List<@NotBlank String> retryOn) {
    }

    public record DefaultsSpec(@Min(100) @Max(120000) Integer timeoutMs, @Valid RetrySpec retry) {
    }
}
