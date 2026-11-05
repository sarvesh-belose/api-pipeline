package com.crisil.apiorch.endpoint;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.config.ApiorchProperties.Resilience.Defaults;
import com.crisil.apiorch.endpoint.dto.EndpointRequest;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.Backoff;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.HttpVersion;

/** Converts between the stored row, the request and the effective {@link EndpointConfig} (US-2.1). */
@Component
public class EndpointMapper {

    private final ObjectMapper json;
    private final Defaults defaults;

    public EndpointMapper(ObjectMapper json, ApiorchProperties properties) {
        this.json = json;
        this.defaults = properties.resilience().defaults();
    }

    void apply(EndpointRequest request, EndpointEntity entity) {
        entity.setDescription(request.description());
        entity.setBaseUrl(request.baseUrl());
        entity.setHealthPath(request.healthPath());
        entity.setConnectTimeoutMs(request.connectTimeoutMs());
        entity.setReadTimeoutMs(request.readTimeoutMs());
        entity.setHttpVersion((request.httpVersion() != null ? request.httpVersion() : HttpVersion.HTTP_1_1).name());
        entity.setEnabled(request.enabled() == null || request.enabled());
        EndpointRequest.Auth auth = request.auth() != null ? request.auth().withoutLiterals() : EndpointRequest.Auth.none();
        entity.setAuthType(auth.type().name());
        entity.setAuthConfig(write(auth));
        entity.setResilienceConfig(request.resilience() != null ? write(request.resilience()) : null);
    }

    public EndpointConfig toConfig(EndpointEntity e) {
        EndpointRequest.Resilience r = e.getResilienceConfig() != null
                ? read(e.getResilienceConfig(), EndpointRequest.Resilience.class) : null;
        return new EndpointConfig(e.getId(), e.getName(), e.getDescription(), e.getBaseUrl(), e.getHealthPath(),
                e.getConnectTimeoutMs(), e.getReadTimeoutMs(), HttpVersion.valueOf(e.getHttpVersion()), e.isEnabled(),
                e.getAuthConfig() != null ? read(e.getAuthConfig(), EndpointRequest.Auth.class)
                        : EndpointRequest.Auth.none(),
                circuitBreaker(r != null ? r.circuitBreaker() : null),
                bulkhead(r != null ? r.bulkhead() : null),
                retry(r != null ? r.retry() : null),
                e.getRowVersion(), e.getCreatedAt(), e.getCreatedBy(), e.getUpdatedAt(), e.getUpdatedBy());
    }

    EndpointConfig.CircuitBreaker circuitBreaker(EndpointRequest.CircuitBreaker c) {
        var d = defaults.circuitBreaker();
        if (c == null) {
            return new EndpointConfig.CircuitBreaker(d.slidingWindowSize(), d.minimumCalls(), d.failureRateThreshold(),
                    d.slowCallDurationMs(), d.slowCallRateThreshold(), d.waitInOpenMs(), d.halfOpenCalls(), true);
        }
        return new EndpointConfig.CircuitBreaker(
                or(c.slidingWindowSize(), d.slidingWindowSize()), or(c.minimumCalls(), d.minimumCalls()),
                or(c.failureRateThreshold(), d.failureRateThreshold()), or(c.slowCallDurationMs(), d.slowCallDurationMs()),
                or(c.slowCallRateThreshold(), d.slowCallRateThreshold()), or(c.waitInOpenMs(), d.waitInOpenMs()),
                or(c.halfOpenCalls(), d.halfOpenCalls()), false);
    }

    EndpointConfig.Bulkhead bulkhead(EndpointRequest.Bulkhead b) {
        var d = defaults.bulkhead();
        if (b == null) {
            return new EndpointConfig.Bulkhead(d.maxConcurrentCalls(), d.maxWaitMs(), true);
        }
        return new EndpointConfig.Bulkhead(or(b.maxConcurrentCalls(), d.maxConcurrentCalls()),
                or(b.maxWaitMs(), d.maxWaitMs()), false);
    }

    EndpointConfig.Retry retry(EndpointRequest.Retry r) {
        var d = defaults.retry();
        Backoff defaultBackoff = Backoff.valueOf(d.backoff());
        if (r == null) {
            return new EndpointConfig.Retry(d.maxAttempts(), defaultBackoff, d.initialMs(), d.maxMs(), true);
        }
        return new EndpointConfig.Retry(or(r.maxAttempts(), d.maxAttempts()),
                r.backoff() != null ? r.backoff() : defaultBackoff,
                or(r.initialMs(), d.initialMs()), or(r.maxMs(), d.maxMs()), false);
    }

    private static int or(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise endpoint settings", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored endpoint settings are not readable", e);
        }
    }
}
