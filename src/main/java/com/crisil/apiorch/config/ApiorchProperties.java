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
        @Valid @DefaultValue Resilience resilience) {

    /** US-1.3. */
    public record Correlation(@DefaultValue("X-Correlation-Id") @NotBlank @Pattern(regexp = "[A-Za-z0-9-]+") String header) {
    }

    /** US-3.1. */
    public record Definition(@DefaultValue("524288") @Min(1024) long maxDocumentBytes) {
    }

    /** US-4.1. */
    public record Run(@DefaultValue("1048576") @Min(1024) long maxInputBytes) {
    }

    /** US-6.2. */
    public record Callback(@DefaultValue("1048576") @Min(1024) long maxBodyBytes) {
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
