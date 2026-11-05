package com.crisil.apiorch.http;

import java.net.http.HttpClient;

import org.springframework.web.client.RestClient;

import com.crisil.apiorch.endpoint.EndpointConfig;

/**
 * Everything needed to call one endpoint, built from its {@link EndpointConfig} (US-2.1): a {@link RestClient}
 * cloned from the shared builder so interceptors never leak between endpoints, and its JDK {@link HttpClient}.
 * US-5.1 to US-5.4 add the timeout buckets and Resilience4j instances. Replaced whole on every change; JDK 17's
 * {@code HttpClient} has no {@code close()}, so a replaced runtime is simply dropped.
 */
public record EndpointRuntime(EndpointConfig config, RestClient restClient, HttpClient httpClient) {

    public String name() {
        return config.name();
    }

    public long version() {
        return config.version();
    }
}
