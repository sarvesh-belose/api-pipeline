package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

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

import com.crisil.apiorch.security.Roles;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-5.3 AC1–AC4, AC6, AC7 against WireMock: 5×503 opens the circuit; a rejected attempt sends nothing and ends at
 * once with {@code CIRCUIT_OPEN} under the step's policy; after {@code waitInOpenMs} probes close or reopen it; 4xx
 * never count; operators read and reset it; a changed endpoint starts closed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CircuitBreakerIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String CIRCUIT = """
            {"slidingWindowSize":10,"minimumCalls":5,"failureRateThreshold":50,"waitInOpenMs":1000,"halfOpenCalls":2}""";

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http http;
    Http ops;
    String endpoint;

    @BeforeEach
    void setUp() throws Exception {
        api.resetAll();
        http = new Http(port);
        ops = http.as(TestJwt.token("ops-user", Roles.OPS));
        endpoint = "score-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = http.postJson("/api/v1/admin/endpoints", """
                {"name":"%s","baseUrl":"http://localhost:%d","connectTimeoutMs":2000,"readTimeoutMs":15000,
                 "resilience":{"circuitBreaker":%s}}""".formatted(endpoint, api.getPort(), CIRCUIT));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    }

    private void respond(int status, int delayMs) {
        api.resetAll();
        api.stubFor(any(urlPathEqualTo("/score")).willReturn(aResponse().withStatus(status).withFixedDelay(delayMs)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    private String pipeline(String onFailure, boolean withSecondStep) throws Exception {
        String code = PublishedFixtures.uniqueCode("CB");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "score", endpoint, "GET", "/score").put("onFailure", onFailure)
                .set("retry", json.createObjectNode().put("maxAttempts", 1));
        if (withSecondStep) {
            // its own endpoint, whose circuit is closed
            String other = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
            HttpPipelines.step(doc, "notify", other, "GET", "/notify").putArray("dependsOn");
        }
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private JsonNode run(String code) throws Exception {
        return json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").body());
    }

    private String state() throws Exception {
        return json.readTree(ops.get("/api/v1/admin/endpoints/" + endpoint + "/circuit").body()).path("state")
                .asText();
    }

    private void open(String code) throws Exception {
        respond(503, 0);
        for (int i = 0; i < 5; i++) {
            assertThat(run(code).at("/errors/0/code").asText()).isEqualTo("HTTP_ERROR");
        }
        assertThat(state()).isEqualTo("OPEN");
    }

    @Test
    void fiveFailuresOpenItAndARejectionSendsNothingAndWaitsForNothing() throws Exception {
        String failRun = pipeline("FAIL_RUN", false);
        open(failRun);

        respond(200, 10_000);
        long start = System.nanoTime();
        JsonNode rejected = run(failRun);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000);
        assertThat(rejected.path("status").asText()).isEqualTo("FAILED");
        assertThat(rejected.at("/errors/0/code").asText()).isEqualTo("CIRCUIT_OPEN");
        api.verify(0, anyRequestedFor(urlPathEqualTo("/score")));
        UUID id = UUID.fromString(rejected.path("runId").asText());
        Map<String, Object> attempt = jdbc.sql("SELECT outcome, duration_ms, circuit_state, event_seq FROM step_attempt "
                + "WHERE run_id = :id").param("id", id).query().singleRow();
        assertThat(attempt).containsEntry("outcome", "CIRCUIT_OPEN").containsEntry("circuit_state", "OPEN");
        assertThat(((Number) attempt.get("duration_ms")).longValue()).isZero();
        String type = jdbc.sql("SELECT event_type FROM run_event WHERE run_id = :id AND seq = :seq").param("id", id)
                .param("seq", ((Number) attempt.get("event_seq")).longValue()).query(String.class).single();
        assertThat(type).isEqualTo("CIRCUIT_OPEN_REJECTED");

        api.stubFor(any(urlPathEqualTo("/notify")).willReturn(aResponse().withStatus(200)));
        JsonNode tolerated = run(pipeline("CONTINUE", true));
        assertThat(tolerated.path("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(tolerated.at("/steps/0/status").asText()).isEqualTo("FAILED");
    }

    @Test
    void afterTheWaitProbesCloseOrReopenIt() throws Exception {
        String code = pipeline("FAIL_RUN", false);
        open(code);
        respond(200, 0);
        await().atMost(Duration.ofSeconds(5)).until(() -> state().equals("HALF_OPEN"));
        assertThat(run(code).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run(code).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(state()).isEqualTo("CLOSED");

        open(code);
        await().atMost(Duration.ofSeconds(5)).until(() -> state().equals("HALF_OPEN"));
        respond(503, 0);
        run(code);
        run(code);
        assertThat(state()).isEqualTo("OPEN");
    }

    @Test
    void clientErrorsNeverCount() throws Exception {
        String code = pipeline("FAIL_RUN", false);
        respond(404, 0);
        for (int i = 0; i < 20; i++) {
            run(code);
        }
        assertThat(state()).isEqualTo("CLOSED");
        api.verify(20, anyRequestedFor(urlPathEqualTo("/score")));
    }

    @Test
    void operatorsReadAndResetIt() throws Exception {
        open(pipeline("FAIL_RUN", false));
        JsonNode view = json.readTree(ops.get("/api/v1/admin/endpoints/" + endpoint + "/circuit").body());
        assertThat(view.path("failedCalls").asInt()).isEqualTo(5);
        assertThat(view.path("failureRate").asDouble()).isEqualTo(100.0);
        assertThat(view.has("nodeId")).isTrue();
        assertThat(view.has("stateSince")).isTrue();

        String path = "/api/v1/admin/endpoints/" + endpoint + "/circuit/reset";
        assertThat(ops.postJson(path, "{}").statusCode()).isEqualTo(400);
        assertThat(http.as(TestJwt.token("system-a", Roles.RUN)).postJson(path, "{\"reason\":\"x\"}").statusCode())
                .isEqualTo(403);
        long before = jdbc.sql("SELECT row_version FROM endpoint WHERE name = :n").param("n", endpoint)
                .query(Long.class).single();
        HttpResponse<String> reset = ops.postJson(path + "?allNodes=true", "{\"reason\":\"KYC back after INC-5012\"}");
        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reset.body()).path("state").asText()).isEqualTo("CLOSED");
        assertThat(jdbc.sql("SELECT row_version FROM endpoint WHERE name = :n").param("n", endpoint)
                .query(Long.class).single()).isEqualTo(before + 1);
    }

    @Test
    void aChangedEndpointStartsClosedWithItsNewThresholds() throws Exception {
        String code = pipeline("FAIL_RUN", false);
        open(code);
        String etag = http.get("/api/v1/admin/endpoints/" + endpoint).headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> updated = http.putJson("/api/v1/admin/endpoints/" + endpoint, """
                {"name":"%s","baseUrl":"http://localhost:%d","connectTimeoutMs":2000,"readTimeoutMs":15000,
                 "resilience":{"circuitBreaker":{"slidingWindowSize":10,"minimumCalls":8,"failureRateThreshold":50,
                 "waitInOpenMs":1000,"halfOpenCalls":2}}}""".formatted(endpoint, api.getPort()), etag);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(state()).isEqualTo("CLOSED");
        respond(503, 0);
        for (int i = 0; i < 7; i++) {
            run(code);
        }
        assertThat(state()).as("8 calls are needed now").isEqualTo("CLOSED");
        run(code);
        assertThat(state()).isEqualTo("OPEN");
    }
}
