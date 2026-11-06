package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.HashMap;
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
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-4.4 AC1–AC3 and AC5 against WireMock: siblings run in parallel, a join waits for all of its dependencies and
 * maps from each, roots start together, and a failed dependency skips its dependents naming it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ParallelStepsIT {

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

    private void respond(String path, int delayMs, String body) {
        api.stubFor(post(path).willReturn(aResponse().withStatus(200).withFixedDelay(delayMs)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private JsonNode run(ObjectNode doc) throws Exception {
        PublishedFixtures.publish(jdbc, doc, 1);
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + doc.path("code").asText() + "/runs?mode=sync",
                "{\"name\":\"Asha\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private record Span(long start, long end) {
    }

    private Map<String, Span> spans(JsonNode run) {
        Map<String, Span> out = new HashMap<>();
        jdbc.sql("SELECT step_id, started_at, ended_at FROM step_execution WHERE run_id = :id")
                .param("id", UUID.fromString(run.path("runId").asText()))
                .query((rs, i) -> out.put(rs.getString("step_id"), new Span(millis(rs.getTimestamp("started_at")),
                        millis(rs.getTimestamp("ended_at"))))).list();
        return out;
    }

    private static long millis(Timestamp t) {
        return t == null ? -1 : t.getTime();
    }

    @Test
    void siblingsOverlapAndTheJoinWaitsForBoth() throws Exception {
        respond("/api1", 0, "{\"id\":\"C-77\"}");
        respond("/api2", 500, "{\"ref\":\"KYC-1\"}");
        respond("/api3", 900, "{\"score\":712}");
        respond("/api4", 0, "{}");
        String code = PublishedFixtures.uniqueCode("PAR");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/api1").put("body", "{}");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/api2").put("body", "{ 'id': steps.api1.body.id }")
                .putArray("dependsOn").add("api1");
        HttpPipelines.step(doc, "api3", endpoint, "POST", "/api3").put("body", "{ 'id': steps.api1.body.id }")
                .putArray("dependsOn").add("api1");
        HttpPipelines.step(doc, "api4", endpoint, "POST", "/api4")
                .put("body", "{ 'kyc': steps.api2.body.ref, 'score': steps.api3.body.score }")
                .putArray("dependsOn").add("api2").add("api3");

        JsonNode run = run(doc);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        Map<String, Span> s = spans(run);
        assertThat(s.get("api2").start()).isGreaterThanOrEqualTo(s.get("api1").end());
        assertThat(s.get("api3").start()).isGreaterThanOrEqualTo(s.get("api1").end());
        assertThat(s.get("api2").start()).isLessThan(s.get("api3").end());
        assertThat(s.get("api3").start()).isLessThan(s.get("api2").end());
        long stage = Math.max(s.get("api2").end(), s.get("api3").end()) - Math.min(s.get("api2").start(),
                s.get("api3").start());
        assertThat(stage).isLessThan(500 + 900 + 400);
        assertThat(s.get("api4").start()).isGreaterThanOrEqualTo(s.get("api3").end());
        api.verify(postRequestedFor(urlEqualTo("/api4")).withRequestBody(equalToJson("{\"kyc\":\"KYC-1\",\"score\":712}")));
    }

    @Test
    void rootsStartTogetherInListOrder() throws Exception {
        respond("/b", 400, "{}");
        respond("/a", 400, "{}");
        String code = PublishedFixtures.uniqueCode("ROOTS");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "b", endpoint, "POST", "/b").put("body", "{}").putArray("dependsOn");
        HttpPipelines.step(doc, "a", endpoint, "POST", "/a").put("body", "{}").putArray("dependsOn");

        JsonNode run = run(doc);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        Map<String, Span> s = spans(run);
        assertThat(s.get("a").start()).isLessThan(s.get("b").end());
        assertThat(s.get("b").start()).isLessThan(s.get("a").end());
        List<String> scheduled = jdbc.sql("SELECT step_id FROM run_event WHERE run_id = :id AND "
                + "event_type = 'STEP_SCHEDULED' ORDER BY seq").param("id", UUID.fromString(run.path("runId").asText()))
                .query(String.class).list();
        assertThat(scheduled).containsExactly("b", "a");
    }

    @Test
    void aFailedDependencySkipsItsDependentsTransitively() throws Exception {
        respond("/api1", 0, "{}");
        api.stubFor(post("/api2").willReturn(aResponse().withStatus(500)));
        respond("/api3", 0, "{}");
        String code = PublishedFixtures.uniqueCode("DEPSKIP");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/api1").put("body", "{}");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/api2").put("body", "{}").put("onFailure", "CONTINUE");
        HttpPipelines.step(doc, "api3", endpoint, "POST", "/api3").put("body", "{}").putArray("dependsOn").add("api1");
        HttpPipelines.step(doc, "api4", endpoint, "POST", "/api4").put("body", "{}").putArray("dependsOn").add("api2");
        HttpPipelines.step(doc, "api5", endpoint, "POST", "/api5").put("body", "{}").putArray("dependsOn").add("api4");

        JsonNode run = run(doc);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.path("steps").findValuesAsText("status"))
                .containsExactly("SUCCEEDED", "FAILED", "SUCCEEDED", "SKIPPED", "SKIPPED");
        assertThat(run.path("steps").findValuesAsText("skipReason")).containsOnly("DEPENDENCY_FAILED");
        List<String> details = jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = 'STEP_SKIPPED' "
                + "ORDER BY seq").param("id", UUID.fromString(run.path("runId").asText())).query(String.class).list();
        assertThat(details).hasSize(2);
        assertThat(details.get(0)).contains("\"dependency\":\"api2\"").contains("DEPENDENCY_FAILED");
        assertThat(details.get(1)).contains("\"dependency\":\"api4\"");
        assertThat(api.findAll(postRequestedFor(urlEqualTo("/api4")))).isEmpty();
    }
}
