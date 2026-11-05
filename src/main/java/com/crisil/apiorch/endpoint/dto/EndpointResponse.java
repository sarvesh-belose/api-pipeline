package com.crisil.apiorch.endpoint.dto;

import java.time.Instant;

import com.crisil.apiorch.endpoint.EndpointConfig;

/** The effective configuration returned by the endpoint API (US-2.1 AC7). Auth secrets never appear (US-2.2). */
public record EndpointResponse(
        String name,
        String description,
        String baseUrl,
        String healthPath,
        int connectTimeoutMs,
        int readTimeoutMs,
        EndpointRequest.HttpVersion httpVersion,
        boolean enabled,
        Auth auth,
        Resilience resilience,
        long version,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {

    public record Auth(EndpointRequest.AuthType type) {
    }

    public record Resilience(EndpointConfig.CircuitBreaker circuitBreaker, EndpointConfig.Bulkhead bulkhead,
            EndpointConfig.Retry retry) {
    }

    public static EndpointResponse of(EndpointConfig c) {
        return new EndpointResponse(c.name(), c.description(), c.baseUrl(), c.healthPath(), c.connectTimeoutMs(),
                c.readTimeoutMs(), c.httpVersion(), c.enabled(), new Auth(c.authType()),
                new Resilience(c.circuitBreaker(), c.bulkhead(), c.retry()), c.version(), c.createdAt(),
                c.createdBy(), c.updatedAt(), c.updatedBy());
    }
}
