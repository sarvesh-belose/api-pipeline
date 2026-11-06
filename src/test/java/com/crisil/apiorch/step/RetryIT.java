package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.List;
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
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-5.2 AC1–AC4 and AC8 against WireMock: transient failures are retried with their events and one attempt row
 * each, permanent ones are not, a sent non-idempotent request is never repeated, {@code Retry-After} is honoured,
 * and every attempt carries the same {@code Idempotency-Key}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RetryIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http http;
    String endpoint;

    @BeforeEach
    void setUp() throws Exception {
        http = new Http(port);
        endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        api.resetAll();
    }

    private JsonNode run(String endpointName, String method, Boolean idempotent, ObjectNode retry) throws Exception {
        String code = PublishedFixtures.uniqueCode("RETRY");
        ObjectNode doc = HttpPipelines.pipeline(code);
        ObjectNode step = HttpPipelines.step(doc, "api2", endpointName, method, "/api2");
        if (!method.equals("GET")) {
            step.put("body", "{}");
        }
        if (idempotent != null) {
            step.put("idempotent", idempotent);
        }
        step.set("retry", retry != null ? retry : fixed(3, 100));
        PublishedFixtures.publish(jdbc, doc, 1);
        return json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").body());
    }

    private ObjectNode fixed(int maxAttempts, int ms) {
        return json.createObjectNode().put("maxAttempts", maxAttempts).put("backoff", "FIXED").put("initialMs", ms)
                .put("maxMs", 5000);
    }

    private void sequence(int... statuses) {
        String state = Scenario.STARTED;
        for (int i = 0; i < statuses.length; i++) {
            String next = "s" + (i + 1);
            api.stubFor(any(urlPathEqualTo("/api2")).inScenario("seq").whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(statuses[i]).withHeader("Content-Type", "application/json")
                            .withBody("{}"))
                    .willSetStateTo(next));
            state = next;
        }
    }

    private List<Map<String, Object>> attempts(JsonNode run) {
        return jdbc.sql("SELECT attempt_no, outcome, response_status, next_retry_delay_ms FROM step_attempt "
                + "WHERE run_id = :id ORDER BY attempt_no").param("id", UUID.fromString(run.path("runId").asText()))
                .query().listOfRows();
    }

    private List<String> events(JsonNode run, String type) {
        return jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = :t ORDER BY seq")
                .param("id", UUID.fromString(run.path("runId").asText())).param("t", type).query(String.class).list();
    }

    private int stepAttempts(JsonNode run) {
        return jdbc.sql("SELECT attempts FROM step_execution WHERE run_id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query(Integer.class).single();
    }

    @Test
    void transientFailuresAreRetriedWithOneRowAndTheSameKeyEach() throws Exception {
        sequence(503, 503, 200);
        JsonNode run = run(endpoint, "GET", null, null);

        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(stepAttempts(run)).isEqualTo(3);
        List<Map<String, Object>> rows = attempts(run);
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("HTTP_ERROR", "HTTP_ERROR", "SUCCESS");
        assertThat(rows.get(0).get("next_retry_delay_ms")).isNotNull();
        assertThat(rows.get(2).get("next_retry_delay_ms")).isNull();
        assertThat(events(run, "ATTEMPT_FAILED")).hasSize(2);
        List<String> scheduled = events(run, "RETRY_SCHEDULED");
        assertThat(scheduled).hasSize(2);
        assertThat(scheduled.get(0)).contains("\"reason\":\"HTTP_503\"").contains("\"attempt\":2").contains("\"delayMs\"");

        List<ServeEvent> served = api.getAllServeEvents();
        assertThat(served).hasSize(3);
        assertThat(served.stream().map(e -> e.getRequest().getHeader("Idempotency-Key")).distinct().toList())
                .containsExactly(run.path("runId").asText() + ":api2:1");
    }

    @Test
    void permanentFailuresAreNotRetried() throws Exception {
        for (int status : new int[] {400, 401, 403, 404, 409, 422}) {
            api.resetAll();
            sequence(status, 200);
            JsonNode run = run(endpoint, "GET", null, null);
            assertThat(run.path("status").asText()).as("%d", status).isEqualTo("FAILED");
            assertThat(run.at("/errors/0/code").asText()).isEqualTo("HTTP_ERROR");
            assertThat(stepAttempts(run)).as("%d", status).isEqualTo(1);
            api.verify(1, anyRequestedFor(urlPathEqualTo("/api2")));
        }
    }

    @Test
    void aSentNonIdempotentRequestIsNeverRepeated() throws Exception {
        sequence(503, 200);
        JsonNode notIdempotent = run(endpoint, "POST", false, null);
        assertThat(notIdempotent.path("status").asText()).isEqualTo("FAILED");
        api.verify(1, anyRequestedFor(urlPathEqualTo("/api2")));

        api.resetAll();
        sequence(503, 200);
        JsonNode idempotent = run(endpoint, "POST", true, null);
        assertThat(idempotent.path("status").asText()).isEqualTo("COMPLETED");
        api.verify(2, anyRequestedFor(urlPathEqualTo("/api2")));

        api.resetAll();
        api.stubFor(any(urlPathEqualTo("/api2")).inScenario("reset").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("ok"));
        api.stubFor(any(urlPathEqualTo("/api2")).inScenario("reset").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)));
        // a POST: the JDK client repeats a GET after a reset by itself, so apiorch would never see the IO_ERROR
        JsonNode reset = run(endpoint, "POST", true, null);
        assertThat(reset.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(attempts(reset)).extracting(r -> r.get("outcome")).containsExactly("IO_ERROR", "SUCCESS");
    }

    @Test
    void aConnectErrorIsRetriedEvenWhenNotIdempotent() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        String down = HttpPipelines.endpoint(http, "http://localhost:" + closed);
        JsonNode run = run(down, "POST", false, null);
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("CONNECT_ERROR");
        assertThat(stepAttempts(run)).isEqualTo(3);
        assertThat(attempts(run)).extracting(r -> r.get("outcome")).containsOnly("CONNECT_ERROR").hasSize(3);
    }

    @Test
    void retryAfterIsHonoured() throws Exception {
        api.stubFor(any(urlPathEqualTo("/api2")).inScenario("429").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("ok"));
        api.stubFor(any(urlPathEqualTo("/api2")).inScenario("429").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)));
        JsonNode run = run(endpoint, "GET", null, fixed(3, 10));
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        List<ServeEvent> served = new java.util.ArrayList<>(api.getAllServeEvents());
        served.sort(java.util.Comparator.comparing(e -> e.getRequest().getLoggedDate()));
        long gap = served.get(1).getRequest().getLoggedDate().getTime() - served.get(0).getRequest().getLoggedDate()
                .getTime();
        assertThat(gap).isGreaterThanOrEqualTo(1000);
        assertThat(events(run, "RETRY_SCHEDULED").get(0)).contains("\"delayMs\":1000").contains("HTTP_429");
    }
}
