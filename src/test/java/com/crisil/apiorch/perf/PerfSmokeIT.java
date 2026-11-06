package com.crisil.apiorch.perf;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * The R1 perf smoke (plan §9): a steady rate of caller-async runs of a three-step HTTP pipeline on one node, to
 * catch gross regressions. Every run must complete, none may fail, and the node must keep up (the backlog at the
 * end drains within a minute). Defaults are the plan's 20 runs/s for 10 minutes; override with
 * {@code -Dperf.rate} and {@code -Dperf.seconds}. Tagged {@code perf}: run with {@code ./mvnw verify -Pperf}.
 * The full NFR proof is US-8.2 (R2).
 */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:perf-smoke;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=30"})
@ActiveProfiles("test")
class PerfSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(PerfSmokeIT.class);

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().containerThreads(100).disableRequestJournal()).build();

    @Value("${local.server.port}") int port;
    @Value("${perf.rate:20}") int rate;
    @Value("${perf.seconds:600}") int seconds;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void aSteadyRateCompletesWithNoFailures() throws Exception {
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(20)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"C-77\"}")));
        Http http = new Http(port);
        // all three steps hit this one endpoint (60 calls/s at the default rate), so it gets a bulkhead sized for
        // that; the default 25 sheds load here, as designed (US-5.4)
        String endpoint = "perf-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        assertThat(http.postJson("/api/v1/admin/endpoints", """
                {"name":"%s","baseUrl":"http://localhost:%d","connectTimeoutMs":2000,"readTimeoutMs":5000,
                 "resilience":{"bulkhead":{"maxConcurrentCalls":100,"maxWaitMs":0}}}"""
                .formatted(endpoint, api.getPort())).statusCode()).isEqualTo(201);
        String code = PublishedFixtures.uniqueCode("PERF");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/customers").put("body", "{ 'name': input.name }");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/kyc").put("body", "{ 'id': steps.api1.body.id }");
        HttpPipelines.step(doc, "api3", endpoint, "GET", "/scores/{steps.api1.body.id}");
        PublishedFixtures.publish(jdbc, doc, 1);

        ExecutorService callers = Executors.newFixedThreadPool(32);
        ConcurrentLinkedQueue<Long> startLatencies = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> refused = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<?>> sent = new ArrayList<>();
        long intervalNanos = 1_000_000_000L / rate;
        long begin = System.nanoTime();
        int total = rate * seconds;
        for (int i = 0; i < total; i++) {
            long due = begin + i * intervalNanos;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
            sent.add(CompletableFuture.runAsync(() -> {
                long t0 = System.nanoTime();
                try {
                    HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=async",
                            "{\"name\":\"perf\"}");
                    startLatencies.add((System.nanoTime() - t0) / 1_000_000);
                    if (res.statusCode() != 202) {
                        refused.add(res.statusCode());
                    }
                } catch (Exception e) {
                    refused.add(-1);
                }
            }, callers));
        }
        CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).get();
        callers.shutdown();
        long sendSeconds = (System.nanoTime() - begin) / 1_000_000_000L;

        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2)).until(() -> jdbc.sql(
                "SELECT COUNT(*) FROM pipeline_run WHERE pipeline_code = :c AND ended_at IS NULL")
                .param("c", code).query(Long.class).single() == 0);
        Map<String, Object> outcome = jdbc.sql("""
                SELECT COUNT(*) AS runs, SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed,
                       MAX(DATEDIFF(MILLISECOND, created_at, ended_at)) AS slowest
                FROM pipeline_run WHERE pipeline_code = :c""").param("c", code).query().singleRow();
        List<Long> latencies = new ArrayList<>(startLatencies);
        Collections.sort(latencies);
        long p95 = latencies.isEmpty() ? -1 : latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        log.info("perf smoke rate={}/s seconds={} sentIn={}s runs={} completed={} refused={} startP95Ms={} "
                + "slowestRunMs={}", rate, seconds, sendSeconds, outcome.get("runs"), outcome.get("completed"),
                refused.size(), p95, outcome.get("slowest"));

        assertThat(refused).as("refused or failed starts").isEmpty();
        assertThat(((Number) outcome.get("runs")).intValue()).isEqualTo(total);
        assertThat(((Number) outcome.get("completed")).intValue()).isEqualTo(total);
        assertThat(sendSeconds).as("the load generator kept the rate").isLessThanOrEqualTo(seconds + seconds / 10 + 2);
    }
}
