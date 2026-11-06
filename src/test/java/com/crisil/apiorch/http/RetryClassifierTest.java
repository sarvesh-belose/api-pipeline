package com.crisil.apiorch.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.crisil.apiorch.definition.PipelineDefinition.Backoff;
import com.crisil.apiorch.definition.PipelineDefinition.RetrySpec;
import com.crisil.apiorch.endpoint.EndpointConfig;

/** US-5.2: the classification table, policy resolution, {@code Retry-After} and the jittered backoff (AC4, AC5). */
class RetryClassifierTest {

    @ParameterizedTest(name = "{0} {1} idempotent={2} -> {3}")
    @CsvSource(nullValues = "-", value = {
            "CONNECT_ERROR, -, false, CONNECT_ERROR",
            "BULKHEAD_FULL, -, false, BULKHEAD_FULL",
            "TIMEOUT, -, true, TIMEOUT",
            "TIMEOUT, -, false, -",
            "IO_ERROR, -, true, IO_ERROR",
            "IO_ERROR, -, false, -",
            "HTTP_ERROR, 502, true, HTTP_502",
            "HTTP_ERROR, 503, true, HTTP_503",
            "HTTP_ERROR, 504, true, HTTP_504",
            "HTTP_ERROR, 429, true, HTTP_429",
            "HTTP_ERROR, 503, false, -",
            "HTTP_ERROR, 500, true, -",
            "HTTP_ERROR, 408, true, -",
            "HTTP_ERROR, 400, true, -",
            "HTTP_ERROR, 401, true, -",
            "HTTP_ERROR, 403, true, -",
            "HTTP_ERROR, 404, true, -",
            "HTTP_ERROR, 409, true, -",
            "HTTP_ERROR, 422, true, -",
            "DEADLINE_EXCEEDED, -, true, -",
            "SUCCESS_CHECK_FAILED, 200, true, -",
            "AUTH_ERROR, -, true, -",
            "EXPRESSION_ERROR, -, true, -",
            "RESPONSE_TOO_LARGE, 200, true, -",
            "CIRCUIT_OPEN, -, true, -"})
    void defaultTable(String outcome, Integer status, boolean idempotent, String reason) {
        assertThat(RetryClassifier.reason(outcome, status, idempotent, null)).isEqualTo(reason);
    }

    @Test
    void retryOnListsOptInAndOptOut() {
        assertThat(RetryClassifier.reason("HTTP_ERROR", 500, true, List.of("500"))).isEqualTo("HTTP_500");
        assertThat(RetryClassifier.reason("HTTP_ERROR", 408, true, List.of("408"))).isEqualTo("HTTP_408");
        assertThat(RetryClassifier.reason("HTTP_ERROR", 500, true, List.of("5XX"))).isEqualTo("HTTP_500");
        assertThat(RetryClassifier.reason("HTTP_ERROR", 404, true, List.of("5XX", "404"))).isNull();
        assertThat(RetryClassifier.reason("CONNECT_ERROR", null, true, List.of("503"))).isNull();
        assertThat(RetryClassifier.reason("BULKHEAD_FULL", null, false, List.of())).isEqualTo("BULKHEAD_FULL");
    }

    @Test
    void policyFieldsResolveStepThenDefaultsThenEndpoint() {
        EndpointConfig.Retry endpoint = new EndpointConfig.Retry(4, com.crisil.apiorch.endpoint.dto.EndpointRequest
                .Backoff.FIXED, 300, 3000, false);
        RetryPolicy p = RetryPolicy.resolve(new RetrySpec(2, null, null, null, null),
                new RetrySpec(null, Backoff.EXPONENTIAL_JITTER, 100, null, List.of("503")), endpoint);
        assertThat(p).isEqualTo(new RetryPolicy(2, RetryPolicy.Backoff.EXPONENTIAL_JITTER, 100, 3000, List.of("503")));
        assertThat(RetryPolicy.resolve(null, null, endpoint))
                .isEqualTo(new RetryPolicy(4, RetryPolicy.Backoff.FIXED, 300, 3000, RetryClassifier.DEFAULT_RETRY_ON));
    }

    @Test
    void fullJitterIsUniformUpToTheExponentialCeiling() {
        RetryPolicy p = new RetryPolicy(10, RetryPolicy.Backoff.EXPONENTIAL_JITTER, 200, 2000, List.of());
        Random seeded = new Random(42);
        long[] ceilings = {200, 400, 800, 1600, 2000, 2000};
        for (int n = 1; n <= ceilings.length; n++) {
            long max = 0;
            long min = Long.MAX_VALUE;
            for (int i = 0; i < 2000; i++) {
                long d = p.delayMs(n, null, seeded);
                assertThat(d).isBetween(0L, ceilings[n - 1]);
                max = Math.max(max, d);
                min = Math.min(min, d);
            }
            assertThat(max).as("attempt %d reaches near its ceiling", n).isGreaterThan(ceilings[n - 1] * 9 / 10);
            assertThat(min).as("attempt %d reaches near zero", n).isLessThan(ceilings[n - 1] / 10);
        }
        assertThat(new RetryPolicy(3, RetryPolicy.Backoff.FIXED, 250, 2000, List.of()).delayMs(3, null, seeded))
                .isEqualTo(250);
    }

    @Test
    void retryAfterIsHonouredUpToMaxMs() {
        RetryPolicy p = new RetryPolicy(3, RetryPolicy.Backoff.EXPONENTIAL_JITTER, 200, 2000, List.of());
        Instant now = Instant.parse("2026-11-06T10:00:00Z");
        assertThat(p.delayMs(1, RetryPolicy.retryAfterMs("1", now), new Random(1))).isEqualTo(1000);
        assertThat(p.delayMs(1, RetryPolicy.retryAfterMs("120", now), new Random(1))).isEqualTo(2000);
        assertThat(RetryPolicy.retryAfterMs("Fri, 06 Nov 2026 10:00:01 GMT", now)).isEqualTo(1000);
        assertThat(RetryPolicy.retryAfterMs("soon", now)).isNull();
        assertThat(RetryPolicy.retryAfterMs(null, now)).isNull();
    }
}
