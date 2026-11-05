package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** US-7.5 AC5: the facade refuses per-run tag keys, and its meters carry only low-cardinality tags. */
class MetricCardinalityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ApiorchMetrics metrics = new ApiorchMetrics(registry);

    @Test
    void perRunTagKeysAreRefused() {
        for (String key : new String[] {"runId", "run_id", "correlationId", "correlation-id", "url", "traceId"}) {
            assertThatThrownBy(() -> metrics.counter("apiorch.x", "x", Tags.of(key, "v")))
                    .as(key).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void facadeMetersUseOnlyAllowedTags() {
        metrics.runFinished("P", "COMPLETED", Duration.ofMillis(120));
        metrics.stepFinished("P", "api1", "kyc", "SUCCEEDED", Duration.ofMillis(40));
        metrics.attempt("kyc", "SUCCESS", 312, 1024);
        metrics.retryScheduled("kyc", "HTTP_503");
        metrics.definitionPublished("P");
        metrics.leaseLost();
        assertThat(registry.get("apiorch.run.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("apiorch.payload.response.bytes").summary().totalAmount()).isEqualTo(1024);
        assertThat(registry.get("apiorch.retry.scheduled").tag("reason", "HTTP_503").counter().count()).isEqualTo(1);
        assertThat(registry.get("apiorch.lease.lost").counter().count()).isEqualTo(1);
        registry.getMeters().forEach(m -> m.getId().getTags().forEach(t -> assertThat(t.getKey())
                .isIn("pipeline", "status", "step", "endpoint", "outcome", "reason", "le")));
    }
}
