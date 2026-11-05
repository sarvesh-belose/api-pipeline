package com.crisil.apiorch.endpoint.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/admin/endpoints} and {@code PUT …/{name}} (US-2.1). Strictly bound: an unknown
 * property is a {@code 400} (US-1.1 AC12). Omitted resilience sub-objects and fields inherit
 * {@code apiorch.resilience.defaults.*}.
 */
public record EndpointRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}$", message = "must match ^[a-z][a-z0-9-]{1,62}$") String name,
        @Size(max = 512) String description,
        @NotBlank @Size(max = 2048) String baseUrl,
        @Size(max = 512) String healthPath,
        @NotNull @Min(100) @Max(120000) Integer connectTimeoutMs,
        @NotNull @Min(100) @Max(120000) Integer readTimeoutMs,
        HttpVersion httpVersion,
        Boolean enabled,
        @Valid Auth auth,
        @Valid Resilience resilience) {

    public enum HttpVersion { HTTP_1_1, HTTP_2 }

    public enum AuthType { NONE, API_KEY, BEARER, BASIC, OAUTH2_CLIENT_CREDENTIALS }

    /** US-2.2 adds the per-type fields; until then only {@code NONE} is accepted. */
    public record Auth(@NotNull AuthType type) {
    }

    public record Resilience(@Valid CircuitBreaker circuitBreaker, @Valid Bulkhead bulkhead, @Valid Retry retry) {
    }

    public record CircuitBreaker(
            @Min(5) @Max(1000) Integer slidingWindowSize,
            @Min(5) @Max(1000) Integer minimumCalls,
            @Min(1) @Max(100) Integer failureRateThreshold,
            @Min(100) @Max(120000) Integer slowCallDurationMs,
            @Min(1) @Max(100) Integer slowCallRateThreshold,
            @Min(1000) @Max(600000) Integer waitInOpenMs,
            @Min(1) @Max(50) Integer halfOpenCalls) {
    }

    public record Bulkhead(@Min(1) @Max(500) Integer maxConcurrentCalls, @Min(0) @Max(1000) Integer maxWaitMs) {
    }

    public enum Backoff { FIXED, EXPONENTIAL, EXPONENTIAL_JITTER }

    public record Retry(
            @Min(1) @Max(10) Integer maxAttempts,
            Backoff backoff,
            @Min(10) @Max(60000) Integer initialMs,
            @Min(10) @Max(60000) Integer maxMs) {
    }
}
