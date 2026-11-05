package com.crisil.apiorch.endpoint;

import java.time.Instant;
import java.util.UUID;

import com.crisil.apiorch.endpoint.dto.EndpointRequest.AuthType;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.Backoff;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.HttpVersion;

/**
 * The effective, immutable configuration of one endpoint: stored values with every omitted resilience setting
 * filled from {@code apiorch.resilience.defaults.*} (US-2.1). The {@code inherited} flags say which sub-objects
 * came entirely from the defaults.
 */
public record EndpointConfig(
        UUID id,
        String name,
        String description,
        String baseUrl,
        String healthPath,
        int connectTimeoutMs,
        int readTimeoutMs,
        HttpVersion httpVersion,
        boolean enabled,
        AuthType authType,
        CircuitBreaker circuitBreaker,
        Bulkhead bulkhead,
        Retry retry,
        long version,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {

    public record CircuitBreaker(int slidingWindowSize, int minimumCalls, int failureRateThreshold,
            int slowCallDurationMs, int slowCallRateThreshold, int waitInOpenMs, int halfOpenCalls,
            boolean inherited) {
    }

    public record Bulkhead(int maxConcurrentCalls, int maxWaitMs, boolean inherited) {
    }

    public record Retry(int maxAttempts, Backoff backoff, int initialMs, int maxMs, boolean inherited) {
    }
}
