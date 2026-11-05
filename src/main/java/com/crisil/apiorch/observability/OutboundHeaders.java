package com.crisil.apiorch.observability;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.crisil.apiorch.config.ApiorchProperties;

/**
 * Headers apiorch adds to every outbound call (US-1.3 AC5): the run's correlation ID under the configured header
 * name. Used by {@code ResilientHttpClient} (US-4.3) and {@code OutboxDispatcher} (US-6.5); the run and step
 * identity headers are added there.
 */
@Component
public class OutboundHeaders {

    private final String header;

    public OutboundHeaders(ApiorchProperties properties) {
        this.header = properties.correlation().header();
    }

    public Map<String, String> correlation(String correlationId) {
        return Map.of(header, correlationId);
    }
}
