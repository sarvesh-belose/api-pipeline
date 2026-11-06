package com.crisil.apiorch.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.micrometer.core.instrument.MeterRegistry;

import com.crisil.apiorch.security.Roles;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-7.4 AC1–AC4, AC9: the timeline of a 5-step run with a retry, {@code include=events}, ownership, attempt detail
 * for {@code apiorch.ops}, and the {@code PAYLOAD_ACCESSED} audit (recorded for detail reads only; a {@code 403}
 * records nothing and is counted).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TimelineIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;
    Http caller;
    Http ops;
    String runId;

    @BeforeEach
    void runFiveSteps() throws Exception {
        api.resetAll();
        api.stubFor(any(anyUrl()).atPriority(5).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"C-77\",\"pan\":\"ABCDE1234F\"}")));
        api.stubFor(any(urlPathEqualTo("/api2")).atPriority(1).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        caller = new Http(port).as(TestJwt.token("system-a", Roles.RUN));
        ops = caller.as(TestJwt.token("ops-user", Roles.OPS));
        String endpoint = HttpPipelines.endpoint(new Http(port), "http://localhost:" + api.getPort());
        String code = PublishedFixtures.uniqueCode("TIMELINE");
        ObjectNode doc = HttpPipelines.pipeline(code);
        doc.putObject("logging").put("capture", "FULL").putArray("maskKeys").add("pan");
        for (String id : List.of("api1", "api2", "api3", "api4", "api5")) {
            ObjectNode step = HttpPipelines.step(doc, id, endpoint, "GET", "/" + id);
            if (id.equals("api2")) {
                step.set("retry", json.createObjectNode().put("maxAttempts", 2).put("backoff", "FIXED")
                        .put("initialMs", 50).put("maxMs", 1000));
            }
        }
        PublishedFixtures.publish(jdbc, doc, 1);
        HttpResponse<String> res = caller.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{\"name\":\"x\"}");
        assertThat(json.readTree(res.body()).path("status").asText()).isEqualTo("COMPLETED");
        runId = json.readTree(res.body()).path("runId").asText();
    }

    private List<String> events(String type) {
        return jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = :t ORDER BY seq")
                .param("id", UUID.fromString(runId)).param("t", type).query(String.class).list();
    }

    @Test
    void theTimelineListsEveryCallInOrderWithTotals() throws Exception {
        HttpResponse<String> res = caller.get("/api/v1/runs/" + runId + "/timeline");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode t = json.readTree(res.body());
        List<String> types = new ArrayList<>();
        t.path("calls").forEach(c -> types.add(c.path("type").asText() + (c.has("stepId")
                ? ":" + c.path("stepId").asText() + "#" + c.path("attempt").asInt() : "")));
        assertThat(types).containsExactly("INBOUND_REQUEST", "REQUEST:api1#1", "REQUEST:api2#1", "REQUEST:api2#2",
                "REQUEST:api3#1", "REQUEST:api4#1", "REQUEST:api5#1", "CALLER_RESPONSE");
        JsonNode first = t.at("/calls/1");
        for (String field : List.of("seq", "execNo", "endpoint", "method", "url", "startedAt", "durationMs",
                "requestBytes", "responseBytes", "httpStatus", "outcome", "circuitState")) {
            assertThat(first.has(field)).as(field).isTrue();
        }
        assertThat(t.at("/calls/2/httpStatus").asInt()).isEqualTo(503);
        assertThat(t.at("/calls/7/final").asBoolean()).isTrue();
        long seq = -1;
        for (JsonNode c : t.path("calls")) {
            assertThat(c.path("seq").asLong()).isGreaterThan(seq);
            seq = c.path("seq").asLong();
        }
        assertThat(t.path("steps")).hasSize(5);
        assertThat(t.path("steps").findValuesAsText("status")).containsOnly("SUCCEEDED");
        assertThat(t.at("/totals/calls").asInt()).isEqualTo(8);
        assertThat(t.at("/totals/responseBytes").asLong()).isPositive();
        assertThat(t.at("/totals/wallTimeMs").asLong()).isGreaterThanOrEqualTo(t.at("/totals/downstreamTimeMs").asLong()
                / 10);
        assertThat(t.has("events")).isFalse();
        assertThat(res.body()).doesNotContain("ABCDE1234F").doesNotContain("\"body\"");

        JsonNode withEvents = json.readTree(ops.get("/api/v1/runs/" + runId + "/timeline?include=events").body());
        assertThat(withEvents.path("events").findValuesAsText("type")).startsWith("RUN_ACCEPTED")
                .contains("RETRY_SCHEDULED", "RUN_COMPLETED");
        assertThat(caller.as(TestJwt.token("system-b", Roles.RUN)).get("/api/v1/runs/" + runId + "/timeline")
                .statusCode()).isEqualTo(404);
        assertThat(events("PAYLOAD_ACCESSED")).isEmpty();
    }

    @Test
    void attemptDetailIsMaskedForOpsAndTheReadIsRecorded() throws Exception {
        double deniedBefore = meters.counter("apiorch.payload.access.denied").count();
        assertThat(caller.get("/api/v1/runs/" + runId + "/steps/api2/attempts/1?kind=REQUEST").statusCode())
                .isEqualTo(403);
        assertThat(meters.counter("apiorch.payload.access.denied").count()).isEqualTo(deniedBefore + 1);
        assertThat(events("PAYLOAD_ACCESSED")).isEmpty();

        HttpResponse<String> res = ops.get("/api/v1/runs/" + runId + "/steps/api1/attempts/1?kind=REQUEST");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode a = json.readTree(res.body());
        assertThat(a.at("/responseBody/pan").asText()).isEqualTo("***");
        assertThat(a.at("/responseBody/id").asText()).isEqualTo("C-77");
        assertThat(a.path("requestHeaders").isObject()).isTrue();
        assertThat(a.path("payloadPurged").asBoolean()).isFalse();
        assertThat(a.path("responseTruncated").asBoolean()).isFalse();
        assertThat(res.body()).doesNotContain("ABCDE1234F");
        assertThat(ops.get("/api/v1/runs/" + runId + "/steps/api1/attempts/9").statusCode()).isEqualTo(404);

        List<String> access = events("PAYLOAD_ACCESSED");
        assertThat(access).hasSize(1);
        assertThat(access.get(0)).contains("\"kind\":\"ATTEMPT\"").contains("\"stepId\":\"api1\"")
                .contains("\"attemptNo\":1");
        assertThat(jdbc.sql("SELECT actor FROM run_event WHERE run_id = :id AND event_type = 'PAYLOAD_ACCESSED'")
                .param("id", UUID.fromString(runId)).query(String.class).single()).isEqualTo("ops-user");

        ops.get("/api/v1/runs/" + runId + "/exchanges/INBOUND_REQUEST");
        assertThat(events("PAYLOAD_ACCESSED")).hasSize(2);
        assertThat(events("PAYLOAD_ACCESSED").get(1)).contains("\"kind\":\"INBOUND_REQUEST\"");
    }
}
