package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-5.2 AC6: a 503 loop with ten attempts and a short {@code runTimeoutMs} stops when the budget is spent; the step
 * reaches a terminal state within {@code runTimeoutMs} + 1 s and the run is {@code TIMED_OUT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RetryBudgetIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void retriesStopAtTheDeadline() throws Exception {
        api.stubFor(any(urlPathEqualTo("/loop")).willReturn(aResponse().withStatus(503)));
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        String code = PublishedFixtures.uniqueCode("BUDGET");
        ObjectNode doc = HttpPipelines.pipeline(code);
        doc.put("runTimeoutMs", 2000);
        HttpPipelines.step(doc, "api1", endpoint, "GET", "/loop").set("retry", json.createObjectNode()
                .put("maxAttempts", 10).put("backoff", "FIXED").put("initialMs", 700).put("maxMs", 5000));
        PublishedFixtures.publish(jdbc, doc, 1);

        long start = System.nanoTime();
        JsonNode run = json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").body());
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(run.path("status").asText()).isEqualTo("TIMED_OUT");
        assertThat(run.at("/steps/0/status").asText()).isIn("CANCELLED", "FAILED");
        assertThat(tookMs).isLessThan(2000 + 1000 + 1500);
        int attempts = jdbc.sql("SELECT attempts FROM step_execution WHERE run_id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query(Integer.class).single();
        assertThat(attempts).isBetween(2, 4);
    }
}
