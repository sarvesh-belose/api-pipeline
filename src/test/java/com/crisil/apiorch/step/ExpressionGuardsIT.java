package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-4.3 AC10 at step level (ADR-002 D1–D5): 32 concurrent runs of one compiled expression each send their own
 * input; unbounded recursion, a huge {@code $pad} and an abort inside a {@code $sort} comparator all fail the step
 * with {@code EXPRESSION_ERROR} within the budget, so the {@code step-io} thread is released.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExpressionGuardsIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Value("${apiorch.expression.timeout-ms:1000}") long timeoutMs;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http http;
    String endpoint;

    @BeforeEach
    void setUp() throws Exception {
        http = new Http(port);
        endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        api.stubFor(post("/echo").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{}")));
    }

    private String publish(String body) {
        String code = PublishedFixtures.uniqueCode("GUARD");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/echo").put("body", body);
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private JsonNode run(String code, String input) throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", input);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    @Test
    void concurrentRunsOfOneExpressionEachSendTheirOwnInput() throws Exception {
        String code = publish("{ 'name': input.name, 'n': input.n * 2 }");
        int n = 32;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        Map<String, Integer> expected = new HashMap<>();
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=async",
                            "{\"name\":\"caller-" + k + "\",\"n\":" + k + "}");
                    return json.readTree(res.body());
                }));
            }
            go.countDown();
            for (int i = 0; i < n; i++) {
                expected.put(futures.get(i).get().path("runId").asText(), i);
            }
        } finally {
            pool.shutdownNow();
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.sql(
                "SELECT COUNT(*) FROM pipeline_run WHERE pipeline_code = :c AND status = 'COMPLETED'")
                .param("c", code).query(Long.class).single() == n);
        int matched = 0;
        for (ServeEvent e : api.getAllServeEvents()) {
            Integer k = expected.get(e.getRequest().getHeader("X-Apiorch-Run-Id"));
            if (k != null) {
                assertThat(json.readTree(e.getRequest().getBodyAsString()))
                        .isEqualTo(json.readTree("{\"name\":\"caller-" + k + "\",\"n\":" + (k * 2) + "}"));
                matched++;
            }
        }
        assertThat(matched).isEqualTo(n);
    }

    @Test
    void hostileExpressionsFailTheStepWithinTheBudget() throws Exception {
        String[] hostile = {
                "{ 'v': ($f := function($x){ $f($x + 1) }; $f(0)) }",
                "{ 'v': $pad('x', 50000000) }",
                "{ 'v': $sort([3, 1, 2], function($a, $b){ $length($pad('x', 50000000)) > 0 }) }"};
        for (String body : hostile) {
            String code = publish(body);
            long start = System.nanoTime();
            JsonNode run = run(code, "{}");
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(run.path("status").asText()).as(body).isEqualTo("FAILED");
            assertThat(run.at("/errors/0/code").asText()).as(body).isEqualTo("EXPRESSION_ERROR");
            assertThat(tookMs).as(body).isLessThan(timeoutMs + 500 + 1500);
        }
        String code = publish("{ 'v': $pad('x', 50000000) }");
        long start = System.nanoTime();
        JsonNode pad = run(code, "{}");
        assertThat(pad.at("/errors/0/message").asText()).contains("D3999");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000 + 1500);
    }
}
