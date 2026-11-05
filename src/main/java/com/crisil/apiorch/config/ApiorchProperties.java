package com.crisil.apiorch.config;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The single typed home for the {@code apiorch.*} keys (README §11). Startup fails on invalid values. Each story
 * adds its own section; defaults match the README catalogue.
 */
@Validated
@ConfigurationProperties("apiorch")
public record ApiorchProperties(
        @Valid @DefaultValue Correlation correlation,
        @Valid @DefaultValue Definition definition,
        @Valid @DefaultValue Run run,
        @Valid @DefaultValue Callback callback,
        @Valid @DefaultValue Endpoints endpoints,
        @Valid @DefaultValue Resilience resilience,
        @Valid @DefaultValue Secrets secrets,
        @Valid @DefaultValue Security security,
        @Valid @DefaultValue Expression expression,
        @Valid @DefaultValue Lease lease,
        @Valid @DefaultValue Engine engine,
        @Valid @DefaultValue Executor executor) {

    /** US-1.3. */
    public record Correlation(@DefaultValue("X-Correlation-Id") @NotBlank @Pattern(regexp = "[A-Za-z0-9-]+") String header) {
    }

    /** US-3.1, US-3.3. */
    public record Definition(
            @DefaultValue("524288") @Min(1024) long maxDocumentBytes,
            @DefaultValue("50") @Min(1) @Max(500) int maxSteps,
            @DefaultValue("5000") @Min(0) long latestCacheTtlMs,
            @DefaultValue("false") boolean requireFourEyes) {
    }

    /** US-4.1. */
    public record Run(
            @DefaultValue("1048576") @Min(1024) long maxInputBytes,
            @DefaultValue("10000") @Min(0) long defaultSyncWaitMs,
            @DefaultValue("30000") @Min(0) long maxSyncWaitMs,
            @DefaultValue("60000") @Min(1000) long defaultRunTimeoutMs,
            @DefaultValue("false") boolean allowVersionOverride,
            @DefaultValue("5242880") @Min(1024) long maxResponseBytes) {
    }

    /** US-5.5. Lease arithmetic uses database time. */
    public record Lease(
            @DefaultValue("30000") @Min(1000) long durationMs,
            @DefaultValue("10000") @Min(100) long renewIntervalMs) {
    }

    /** README §5.1 rule 1: the bounded row-lock wait (ADR-003 F5). */
    public record Engine(@DefaultValue("2000") @Min(100) long lockTimeoutMs) {
    }

    /** US-4.3: the fixed-size {@code step-io} pool that runs blocking step calls. */
    public record Executor(@Valid @DefaultValue StepIo stepIo) {

        public record StepIo(@DefaultValue("64") @Min(1) int size, @DefaultValue("1000") @Min(0) int queueCapacity) {
        }
    }

    /** US-3.2, US-4.3 (ADR-002 D2, D4). The wall-clock budget is validated at 500–5000 ms. */
    public record Expression(
            @DefaultValue("1000") @Min(500) @Max(5000) long timeoutMs,
            @DefaultValue("200") @Min(10) @Max(10000) int maxDepth,
            @DefaultValue("4096") @Min(64) int maxLength,
            @DefaultValue("64") @Min(4) int maxNesting) {
    }

    /** US-6.1, US-6.2 and the US-1.4 public-address guard. */
    public record Callback(
            @DefaultValue("1048576") @Min(1024) long maxBodyBytes,
            String publicBaseUrl,
            String expectedPublicHost) {
    }

    /**
     * US-2.1. {@code allowedHosts} entries are exact host names or {@code *.suffix}; an endpoint base URL must match
     * one. Empty means no endpoint can be registered.
     */
    public record Endpoints(
            @DefaultValue("false") boolean allowHttp,
            @DefaultValue List<@NotBlank String> allowedHosts,
            @DefaultValue("10000") @Min(100) long refreshIntervalMs) {
    }

    /** US-1.2. {@code dev} is refused unless the {@code local} profile is active. */
    public record Security(
            @DefaultValue("jwt") @NotNull Mode mode,
            @DefaultValue("roles") @NotBlank String rolesClaim,
            @DefaultValue("sub") @NotBlank String principalClaim,
            @DefaultValue({"10.0.0.0/8", "127.0.0.1/32", "::1/128"}) List<@NotBlank String> metricsAllowedCidrs) {

        public enum Mode { JWT, DEV }
    }

    /** US-2.2: where secret references may point, and how long resolved values are cached. */
    public record Secrets(
            @DefaultValue("APIORCH_SECRET_") @NotBlank String envPrefix,
            @DefaultValue("/run/secrets") @NotBlank String fileDir,
            @DefaultValue("300000") @Min(0) long cacheTtlMs) {
    }

    /** US-5.2 to US-5.4: the defaults an endpoint inherits for any resilience setting it does not declare. */
    public record Resilience(@Valid @DefaultValue Defaults defaults) {

        public record Defaults(
                @Valid @DefaultValue CircuitBreaker circuitBreaker,
                @Valid @DefaultValue Bulkhead bulkhead,
                @Valid @DefaultValue Retry retry) {
        }

        public record CircuitBreaker(
                @DefaultValue("20") @Min(5) @Max(1000) int slidingWindowSize,
                @DefaultValue("10") @Min(5) @Max(1000) int minimumCalls,
                @DefaultValue("50") @Min(1) @Max(100) int failureRateThreshold,
                @DefaultValue("3000") @Min(100) @Max(120000) int slowCallDurationMs,
                @DefaultValue("80") @Min(1) @Max(100) int slowCallRateThreshold,
                @DefaultValue("30000") @Min(1000) @Max(600000) int waitInOpenMs,
                @DefaultValue("3") @Min(1) @Max(50) int halfOpenCalls) {
        }

        public record Bulkhead(
                @DefaultValue("25") @Min(1) @Max(500) int maxConcurrentCalls,
                @DefaultValue("0") @Min(0) @Max(1000) int maxWaitMs) {
        }

        public record Retry(
                @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
                @DefaultValue("EXPONENTIAL_JITTER") @NotNull String backoff,
                @DefaultValue("200") @Min(10) @Max(60000) int initialMs,
                @DefaultValue("2000") @Min(10) @Max(60000) int maxMs) {
        }
    }
}
