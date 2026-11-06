package com.crisil.apiorch.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.crisil.apiorch.endpoint.EndpointConfig;

/**
 * Everything needed to call one endpoint, built from its {@link EndpointConfig} (US-2.1): a {@link RestClient}
 * cloned from the shared builder so interceptors never leak between endpoints, its JDK {@link HttpClient}, this
 * node's {@link CircuitBreaker} (US-5.3) and {@link Bulkhead} (US-5.4), and a client per timeout bucket (US-5.1). Replaced whole on every change,
 * so a changed endpoint starts with a closed circuit and its new thresholds; JDK 17's {@code HttpClient} has no
 * {@code close()}, so a replaced runtime is simply dropped.
 */
public record EndpointRuntime(EndpointConfig config, RestClient restClient, HttpClient httpClient,
        CircuitBreaker circuitBreaker, Bulkhead bulkhead, Map<Long, RestClient> buckets) {

    static final long BUCKET_MS = 250;
    static final long MAX_BUCKET_MS = 120_000;

    public EndpointRuntime(EndpointConfig config, RestClient restClient, HttpClient httpClient,
            CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        this(config, restClient, httpClient, circuitBreaker, bulkhead, new ConcurrentHashMap<>());
    }

    /** With a default circuit breaker, for tests that only need the clients. */
    public EndpointRuntime(EndpointConfig config, RestClient restClient, HttpClient httpClient) {
        this(config, restClient, httpClient,
                CircuitBreaker.ofDefaults(config != null ? config.name() : "unnamed"),
                Bulkhead.ofDefaults(config != null ? config.name() : "unnamed"));
    }

    public String name() {
        return config.name();
    }

    public long version() {
        return config.version();
    }

    /** {@code timeoutMs} rounded up to 250 ms, at most 120 s, so few clients serve every attempt timeout. */
    public static long bucket(long timeoutMs) {
        long rounded = (Math.max(1, timeoutMs) + BUCKET_MS - 1) / BUCKET_MS * BUCKET_MS;
        return Math.min(rounded, MAX_BUCKET_MS);
    }

    /**
     * The endpoint's client with a read timeout of {@link #bucket}{@code (timeoutMs)}. {@code RestClient} has no
     * per-request timeout, so each bucket shares the {@code HttpClient} through its own request factory; mutating
     * keeps the interceptors (exchange recorder, auth).
     */
    public RestClient restClient(long timeoutMs) {
        return buckets.computeIfAbsent(bucket(timeoutMs), b -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMillis(b));
            return restClient.mutate().requestFactory(factory).build();
        });
    }
}
