package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
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

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-5.1 AC3, AC4 and AC6: with {@code runTimeoutMs: 5000}, two 500 ms steps and a 10 s third (wider than the
 * story's 3000/1 s/5 s so a loaded machine keeps the first two inside the budget), the third attempt is
 * capped by the budget and ends {@code DEADLINE_EXCEEDED}; the step is {@code CANCELLED} when idempotent, else
 * {@code UNKNOWN}; the run is {@code TIMED_OUT} with {@code RUN_TIMEOUT}, and the sync caller gets {@code 200} with
 * the partial output. Repeated to show the outcome is deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunDeadlineIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final int REPETITIONS = 5;

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
        api.stubFor(any(urlPathEqualTo("/api1")).willReturn(json(500, "{\"id\":\"C-77\"}")));
        api.stubFor(any(urlPathEqualTo("/api2")).willReturn(json(500, "{\"kyc\":\"OK\"}")));
        api.stubFor(any(urlPathEqualTo("/api3")).willReturn(json(10000, "{}")));
        api.stubFor(any(urlPathEqualTo("/api4")).willReturn(json(0, "{}")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int delay, String body) {
        return aResponse().withStatus(200).withFixedDelay(delay).withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private JsonNode run(String api3Method, Boolean idempotent) throws Exception {
        String code = PublishedFixtures.uniqueCode("DEADLINE");
        ObjectNode doc = HttpPipelines.pipeline(code);
        doc.put("runTimeoutMs", 5000);
        doc.put("output", "{ 'customerId': steps.api1.body.id, 'kyc': steps.api2.body.kyc }");
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/api1").put("body", "{}");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/api2").put("body", "{}");
        ObjectNode api3 = HttpPipelines.step(doc, "api3", endpoint, api3Method, "/api3");
        if (!api3Method.equals("GET")) {
            api3.put("body", "{}");
        }
        if (idempotent != null) {
            api3.put("idempotent", idempotent);
        }
        HttpPipelines.step(doc, "api4", endpoint, "POST", "/api4").put("body", "{}");
        PublishedFixtures.publish(jdbc, doc, 1);
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Apiorch-Run-Status")).contains("TIMED_OUT");
        return json.readTree(res.body());
    }

    private Map<String, Object> runRow(JsonNode run) {
        return jdbc.sql("SELECT stop_reason, error_code FROM pipeline_run WHERE id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query().singleRow();
    }

    private JsonNode timedOutEvent(JsonNode run) throws Exception {
        return json.readTree(jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = 'RUN_TIMED_OUT'")
                .param("id", UUID.fromString(run.path("runId").asText())).query(String.class).single());
    }

    @Test
    void anIdempotentStepCutByTheDeadlineIsCancelled() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            JsonNode run = run("GET", null);
            assertThat(run.path("status").asText()).isEqualTo("TIMED_OUT");
            assertThat(run.path("steps").findValuesAsText("status"))
                    .containsExactly("SUCCEEDED", "SUCCEEDED", "CANCELLED", "CANCELLED");
            assertThat(run.path("output")).isEqualTo(json.readTree("{\"customerId\":\"C-77\",\"kyc\":\"OK\"}"));
            assertThat(run.path("errors").findValuesAsText("code")).contains("RUN_TIMEOUT");
            assertThat(runRow(run)).containsEntry("stop_reason", "DEADLINE").containsEntry("error_code", "RUN_TIMEOUT");
            String outcome = jdbc.sql("SELECT outcome FROM step_attempt WHERE run_id = :id AND step_id = 'api3'")
                    .param("id", UUID.fromString(run.path("runId").asText())).query(String.class).single();
            assertThat(outcome).isEqualTo("DEADLINE_EXCEEDED");
            JsonNode event = timedOutEvent(run);
            assertThat(event.path("aborted").toString()).isEqualTo("[\"api3\"]");
            assertThat(event.has("deadlineAt")).isTrue();
        }
        api.verify(0, postRequestedFor(urlEqualTo("/api4")));
    }

    @Test
    void aSentNonIdempotentStepCutByTheDeadlineIsUnknown() throws Exception {
        JsonNode run = run("POST", false);
        assertThat(run.path("status").asText()).isEqualTo("TIMED_OUT");
        assertThat(run.path("steps").findValuesAsText("status"))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "UNKNOWN", "CANCELLED");
        assertThat(run.path("errors").findValuesAsText("code")).contains("STEP_OUTCOME_UNKNOWN", "RUN_TIMEOUT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = 'STEP_OUTCOME_UNKNOWN'")
                .param("id", UUID.fromString(run.path("runId").asText())).query(Long.class).single()).isEqualTo(1);
        assertThat(timedOutEvent(run).path("unknown").toString()).isEqualTo("[\"api3\"]");
        api.verify(1, postRequestedFor(urlEqualTo("/api3")));
    }
}
