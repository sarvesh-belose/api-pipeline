package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
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
 * US-7.2 with US-4.3: one complete {@code step_attempt} row per call (AC1), its timing (AC4), connect errors (AC5),
 * the same transaction as the step outcome (AC6), {@code attempts} matching the rows (AC7), and what each capture
 * level keeps, with credential headers masked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AttemptCaptureIT {

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
    }

    private JsonNode run(ObjectNode doc) throws Exception {
        PublishedFixtures.publish(jdbc, doc, 1);
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + doc.path("code").asText() + "/runs?mode=sync",
                "{}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private List<Map<String, Object>> attempts(String runId) {
        return jdbc.sql("SELECT * FROM step_attempt WHERE run_id = :id ORDER BY event_seq")
                .param("id", UUID.fromString(runId)).query().listOfRows();
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    void oneCompleteRowPerCallWithTrueSizesAndTiming() throws Exception {
        String responseBody = "{\"pad\":\"" + "y".repeat(1024 - 10) + "\"}";
        api.stubFor(post("/kyc").willReturn(aResponse().withStatus(201).withFixedDelay(750)
                .withHeader("Content-Type", "application/json").withBody(responseBody)));
        String code = PublishedFixtures.uniqueCode("ATT");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/kyc").put("body", "{ 'customerId': 'C-77' }");
        JsonNode run = run(doc);
        String runId = run.path("runId").asText();

        List<Map<String, Object>> rows = attempts(runId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("step_id", "api1").containsEntry("kind", "REQUEST")
                .containsEntry("endpoint_name", endpoint).containsEntry("method", "POST")
                .containsEntry("outcome", "SUCCESS").containsEntry("circuit_state", "CLOSED")
                .containsEntry("response_status", 201)
                .containsEntry("url", "http://localhost:" + api.getPort() + "/kyc");
        assertThat(num(row.get("exec_no"))).isEqualTo(1);
        assertThat(num(row.get("attempt_no"))).isEqualTo(1);
        assertThat(num(row.get("request_bytes"))).isEqualTo("{\"customerId\":\"C-77\"}".length());
        assertThat(num(row.get("response_bytes"))).isEqualTo(1024);
        assertThat(num(row.get("duration_ms"))).isBetween(750L, 1499L);
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("ended_at")).isNotNull();
        assertThat(row.get("request_body")).as("METADATA keeps no bodies").isNull();
        assertThat(row.get("request_headers")).as("METADATA keeps no headers").isNull();

        long requestSent = jdbc.sql("SELECT seq FROM run_event WHERE run_id = :id AND event_type = 'REQUEST_SENT'")
                .param("id", UUID.fromString(runId)).query(Long.class).single();
        assertThat(num(row.get("event_seq"))).isEqualTo(requestSent);
        assertThat(run.at("/steps/0/attempts").asInt()).isEqualTo(rows.size());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = 'STEP_SUCCEEDED' "
                + "AND seq > :seq").param("id", UUID.fromString(runId)).param("seq", requestSent)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void fullCaptureKeepsBodiesAndHeadersButMasksCredentials() throws Exception {
        api.stubFor(post("/full").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("Set-Cookie", "s=1").withBody("{\"ok\":true}")));
        String code = PublishedFixtures.uniqueCode("ATT");
        ObjectNode doc = HttpPipelines.pipeline(code);
        doc.putObject("logging").put("capture", "FULL");
        ObjectNode step = HttpPipelines.step(doc, "api1", endpoint, "POST", "/full");
        step.put("body", "{ 'a': 1 }");
        step.putObject("headers").put("Authorization", "'Bearer secret-token'").put("X-Plain", "'visible'");
        String runId = run(doc).path("runId").asText();
        Map<String, Object> row = attempts(runId).get(0);
        assertThat(row.get("request_body")).isEqualTo("{\"a\":1}");
        assertThat(row.get("response_body")).isEqualTo("{\"ok\":true}");
        String requestHeaders = (String) row.get("request_headers");
        assertThat(requestHeaders).contains("visible").doesNotContain("secret-token").contains("***");
        assertThat((String) row.get("response_headers")).doesNotContain("s=1");
    }

    @Test
    void headersLevelKeepsHeadersOnly() throws Exception {
        api.stubFor(post("/hdr").willReturn(aResponse().withStatus(200).withBody("plain text")));
        String code = PublishedFixtures.uniqueCode("ATT");
        ObjectNode doc = HttpPipelines.pipeline(code);
        ObjectNode step = HttpPipelines.step(doc, "api1", endpoint, "POST", "/hdr");
        step.put("body", "{ 'a': 1 }");
        step.putObject("logging").put("capture", "HEADERS");
        JsonNode run = run(doc);
        Map<String, Object> row = attempts(run.path("runId").asText()).get(0);
        assertThat((String) row.get("request_headers")).contains("X-Apiorch-Step-Id");
        assertThat(row.get("request_body")).isNull();
        assertThat(row.get("response_body")).isNull();
        assertThat(run.at("/output/api1").asText()).as("non-JSON body is a string").isEqualTo("plain text");
    }

    @Test
    void connectErrorIsRecordedWithoutAResponse() throws Exception {
        String closed = HttpPipelines.endpoint(http, "http://localhost:1");
        String code = PublishedFixtures.uniqueCode("ATT");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", closed, "POST", "/x").put("body", "{}");
        JsonNode run = run(doc);
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("CONNECT_ERROR");
        Map<String, Object> row = attempts(run.path("runId").asText()).get(0);
        assertThat(row.get("response_status")).isNull();
        assertThat(row).containsEntry("outcome", "CONNECT_ERROR");
        assertThat((String) row.get("error_class")).isNotBlank();
        assertThat(((String) row.get("error_message")).length()).isLessThanOrEqualTo(1000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = 'RESPONSE_RECEIVED'")
                .param("id", UUID.fromString(run.path("runId").asText())).query(Integer.class).single()).isZero();
    }
}
