package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-5.4 AC1 and AC2: a bulkhead of 5 lets at most 5 calls into a 1 s endpoint at once; the others are
 * {@code BULKHEAD_FULL} attempts with {@code BULKHEAD_REJECTED}, retried with backoff, and all 20 runs succeed. A
 * second endpoint keeps its latency while the first is saturated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BulkheadIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().containerThreads(64)).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private String endpoint(Http http, String prefix, int maxConcurrentCalls) throws Exception {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(http.postJson("/api/v1/admin/endpoints", """
                {"name":"%s","baseUrl":"http://localhost:%d","connectTimeoutMs":2000,"readTimeoutMs":10000,
                 "resilience":{"bulkhead":{"maxConcurrentCalls":%d,"maxWaitMs":0},
                               "circuitBreaker":{"slidingWindowSize":1000,"minimumCalls":1000}}}"""
                .formatted(name, api.getPort(), maxConcurrentCalls)).statusCode()).isEqualTo(201);
        return name;
    }

    private String pipeline(String endpoint, String path) {
        String code = PublishedFixtures.uniqueCode("BH");
        ObjectNode doc = HttpPipelines.pipeline(code);
        doc.put("runTimeoutMs", 60000);
        HttpPipelines.step(doc, "call", endpoint, "GET", path).set("retry", json.createObjectNode()
                .put("maxAttempts", 10).put("backoff", "EXPONENTIAL_JITTER").put("initialMs", 300).put("maxMs", 3000));
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private List<UUID> start(Http http, String code, int n) throws Exception {
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            runs.add(UUID.fromString(json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}")
                    .body()).path("runId").asText()));
        }
        return runs;
    }

    private boolean allCompleted(List<UUID> runs) {
        return runs.stream().allMatch(id -> "COMPLETED".equals(jdbc.sql("SELECT status FROM pipeline_run WHERE id = :id")
                .param("id", id).query(String.class).single()));
    }

    /** The most requests in flight at once at {@code path}, from WireMock's own arrival times and delay. */
    private static int peak(String path, long durationMs) {
        List<long[]> spans = new ArrayList<>();
        for (ServeEvent e : api.getAllServeEvents()) {
            if (e.getRequest().getUrl().equals(path)) {
                long start = e.getRequest().getLoggedDate().getTime();
                spans.add(new long[] {start, start + durationMs});
            }
        }
        int peak = 0;
        for (long[] s : spans) {
            int at = 0;
            for (long[] o : spans) {
                if (o[0] <= s[0] && s[0] < o[1]) {
                    at++;
                }
            }
            peak = Math.max(peak, at);
        }
        return peak;
    }

    private List<Long> stepDurations(List<UUID> runs) {
        List<Long> out = new ArrayList<>();
        for (UUID id : runs) {
            out.add(jdbc.sql("SELECT duration_ms FROM step_attempt WHERE run_id = :id AND outcome = 'SUCCESS'")
                    .param("id", id).query(Long.class).single());
        }
        out.sort(Long::compare);
        return out;
    }

    private static long p95(List<Long> sorted) {
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    @Test
    void atMostFiveCallsAreInFlightAndTheRestRetry() throws Exception {
        api.resetAll();
        api.stubFor(any(urlPathEqualTo("/score")).willReturn(aResponse().withStatus(200).withFixedDelay(1000)));
        Http http = new Http(port);
        String code = pipeline(endpoint(http, "score", 5), "/score");
        List<UUID> runs = start(http, code, 20);

        await().atMost(Duration.ofSeconds(60)).until(() -> allCompleted(runs));
        assertThat(peak("/score", 1000)).isLessThanOrEqualTo(5);
        long rejected = 0;
        for (UUID id : runs) {
            rejected += jdbc.sql("SELECT COUNT(*) FROM step_attempt WHERE run_id = :id AND outcome = 'BULKHEAD_FULL'")
                    .param("id", id).query(Long.class).single();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM step_attempt WHERE run_id = :id AND outcome = 'BULKHEAD_FULL'")
                    .param("id", id).query(Long.class).single())
                    .isEqualTo(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = "
                            + "'BULKHEAD_REJECTED'").param("id", id).query(Long.class).single());
        }
        assertThat(rejected).isPositive();
    }

    @Test
    void anotherEndpointKeepsItsLatencyWhileOneIsSaturated() throws Exception {
        api.resetAll();
        api.stubFor(any(urlPathEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
        api.stubFor(any(urlPathEqualTo("/kyc")).willReturn(aResponse().withStatus(200).withFixedDelay(50)));
        Http http = new Http(port);
        String kyc = pipeline(endpoint(http, "kyc", 25), "/kyc");
        String slow = pipeline(endpoint(http, "slow", 3), "/slow");

        List<UUID> baselineRuns = start(http, kyc, 20);
        await().atMost(Duration.ofSeconds(30)).until(() -> allCompleted(baselineRuns));
        long baseline = p95(stepDurations(baselineRuns));

        List<UUID> saturating = start(http, slow, 20);
        List<UUID> measured = start(http, kyc, 20);
        await().atMost(Duration.ofSeconds(30)).until(() -> allCompleted(measured));
        long saturated = p95(stepDurations(measured));
        assertThat(saturated).as("kyc p95 %d ms against a baseline of %d ms", saturated, baseline)
                .isLessThanOrEqualTo(baseline + 100);
        // drain the saturating runs; some may use up their retries, which is not what this test is about
        await().atMost(Duration.ofSeconds(90)).until(() -> saturating.stream().allMatch(id -> jdbc.sql(
                "SELECT COUNT(*) FROM pipeline_run WHERE id = :id AND ended_at IS NOT NULL").param("id", id)
                .query(Long.class).single() == 1));
    }
}
