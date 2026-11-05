package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-4.3 against WireMock: request order (AC1), whole- and part-field mapping (AC2, AC3), path and query encoding
 * (AC4), outbound headers (AC5), success checks and HTTP errors (AC6), expression errors (AC7), the event sequence
 * for v1 and the reordered v2 (AC8), and large integers (AC9).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SequentialRunIT {

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

    private String publish(ObjectNode doc, int version) {
        PublishedFixtures.publish(jdbc, doc, version);
        return doc.path("code").asText();
    }

    private JsonNode run(String code, String input) throws Exception {
        HttpResponse<String> res = http.send("POST", "/api/v1/pipelines/" + code + "/runs?mode=sync", input, null,
                "X-Correlation-Id", "corr-4-3");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private List<String> requestPaths() {
        List<ServeEvent> events = new ArrayList<>(api.getAllServeEvents());
        java.util.Collections.reverse(events);
        return events.stream().map(e -> e.getRequest().getUrl()).toList();
    }

    private List<String> events(String runId) {
        return jdbc.sql("SELECT event_type, step_id FROM run_event WHERE run_id = :id ORDER BY seq")
                .param("id", UUID.fromString(runId))
                .query((rs, i) -> rs.getString("event_type") + (rs.getString("step_id") == null ? ""
                        : ":" + rs.getString("step_id")))
                .list();
    }

    private ObjectNode five(String code, String... order) {
        ObjectNode doc = HttpPipelines.pipeline(code);
        for (String id : order) {
            ObjectNode step = HttpPipelines.step(doc, id, endpoint, "POST", "/" + id);
            step.put("body", id.equals("api1") ? "{ 'name': input.name }"
                    : id.equals("api2") ? "{ 'customerId': steps.api1.body.id, 'name': input.name }"
                    : "{ 'customerId': steps.api1.body.id }");
        }
        return doc;
    }

    private void stubFive() {
        api.stubFor(post("/api1").willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"C-77\",\"segment\":\"RETAIL\"}")));
        for (String id : List.of("api2", "api3", "api4", "api5")) {
            api.stubFor(post("/" + id).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json").withBody("{\"step\":\"" + id + "\"}")));
        }
    }

    @Test
    void stepsRunInOrderWithMappedBodiesAndAGapFreeEventSequence() throws Exception {
        stubFive();
        String code = PublishedFixtures.uniqueCode("SEQ");
        publish(five(code, "api1", "api2", "api3", "api4", "api5"), 1);
        JsonNode run = run(code, "{\"name\":\"Asha\"}");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(requestPaths()).containsExactly("/api1", "/api2", "/api3", "/api4", "/api5");
        api.verify(postRequestedFor(urlEqualTo("/api2"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"customerId\":\"C-77\",\"name\":\"Asha\"}")));
        assertThat(run.at("/output/api1/segment").asText()).isEqualTo("RETAIL");

        List<String> expected = new ArrayList<>(List.of("RUN_ACCEPTED", "RUN_STARTED"));
        for (String id : List.of("api1", "api2", "api3", "api4", "api5")) {
            expected.addAll(List.of("STEP_SCHEDULED:" + id, "REQUEST_SENT:" + id, "RESPONSE_RECEIVED:" + id,
                    "STEP_SUCCEEDED:" + id));
        }
        expected.add("RUN_COMPLETED");
        String runId = run.path("runId").asText();
        assertThat(events(runId)).containsExactlyElementsOf(expected);
        assertThat(jdbc.sql("SELECT MIN(seq), MAX(seq), COUNT(*) FROM run_event WHERE run_id = :id")
                .param("id", UUID.fromString(runId)).query((rs, i) -> List.of(rs.getLong(1), rs.getLong(2),
                        rs.getLong(3))).single()).containsExactly(1L, 23L, 23L);

        // the reordered version, under its own code: a fixture insert does not evict the latest-version cache
        api.resetRequests();
        String reordered = PublishedFixtures.uniqueCode("SEQ");
        publish(five(reordered, "api1", "api2", "api5", "api4", "api3"), 1);
        JsonNode v2 = run(reordered, "{\"name\":\"Asha\"}");
        assertThat(requestPaths()).containsExactly("/api1", "/api2", "/api5", "/api4", "/api3");
        assertThat(events(v2.path("runId").asText()).stream().filter(e -> e.startsWith("STEP_SCHEDULED")).toList())
                .containsExactly("STEP_SCHEDULED:api1", "STEP_SCHEDULED:api2", "STEP_SCHEDULED:api5",
                        "STEP_SCHEDULED:api4", "STEP_SCHEDULED:api3");
    }

    @Test
    void partOfAFieldAndAResponseHeaderAreMapped() throws Exception {
        api.stubFor(post("/accounts").willReturn(aResponse().withStatus(201).withHeader("Location", "/accounts/77")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"account\":{\"number\":\"ACC-0012345\",\"holders\":[{\"pan\":\"ABCDE1234F\"}]}}")));
        api.stubFor(post("/kyc").willReturn(aResponse().withStatus(200)));
        String code = PublishedFixtures.uniqueCode("PART");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/accounts").put("body", "{}");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/kyc").put("body",
                "{ 'branch': $substring(steps.api1.body.account.number, 4, 3), "
                        + "'pan': steps.api1.body.account.holders[0].pan, 'ref': steps.api1.headers.Location }");
        publish(doc, 1);
        assertThat(run(code, "{}").path("status").asText()).isEqualTo("COMPLETED");
        api.verify(postRequestedFor(urlEqualTo("/kyc")).withRequestBody(
                equalToJson("{\"branch\":\"001\",\"pan\":\"ABCDE1234F\",\"ref\":\"/accounts/77\"}")));
    }

    private String pathPipeline(String id) {
        api.stubFor(post("/customers").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + id + "\",\"segment\":\"RETAIL\"}")));
        api.stubFor(get(urlPathEqualTo("/scores/C-77")).willReturn(aResponse().withStatus(200)));
        api.stubFor(get(urlPathEqualTo("/scores/A%2FB")).willReturn(aResponse().withStatus(200)));
        String code = PublishedFixtures.uniqueCode("PATH");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/customers").put("body", "{}");
        HttpPipelines.step(doc, "api2", endpoint, "GET", "/scores/{steps.api1.body.id}").putObject("query")
                .put("segment", "steps.api1.body.segment");
        publish(doc, 1);
        return code;
    }

    @Test
    void pathAndQueryAreBuiltAndEncoded() throws Exception {
        assertThat(run(pathPipeline("C-77"), "{}").path("status").asText()).isEqualTo("COMPLETED");
        api.verify(getRequestedFor(urlEqualTo("/scores/C-77?segment=RETAIL")));

        api.resetRequests();
        assertThat(run(pathPipeline("A/B"), "{}").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(requestPaths()).contains("/scores/A%2FB?segment=RETAIL");
    }

    @Test
    void unsafePathSegmentFailsTheStepWithoutSending() throws Exception {
        for (String unsafe : List.of("..", ".", "")) {
            api.resetRequests();
            JsonNode run = run(pathPipeline(unsafe), "{}");
            assertThat(run.path("status").asText()).isEqualTo("FAILED");
            assertThat(run.at("/errors/0/code").asText()).isEqualTo("EXPRESSION_ERROR");
            assertThat(run.at("/errors/0/message").asText()).contains("unsafe path segment");
            assertThat(requestPaths()).containsExactly("/customers");
        }
    }

    @Test
    void outboundHeadersAreSetAndProtected() throws Exception {
        api.stubFor(post("/h").willReturn(aResponse().withStatus(204)));
        String code = PublishedFixtures.uniqueCode("HDR");
        ObjectNode doc = HttpPipelines.pipeline(code);
        ObjectNode step = HttpPipelines.step(doc, "api1", endpoint, "POST", "/h");
        step.put("body", "{ 'a': 1 }");
        step.putObject("headers").put("X-Tenant", "'t-' & input.tenant").put("Idempotency-Key", "'evil'")
                .put("X-Apiorch-Run-Id", "'evil'").put("X-Empty", "input.missing");
        publish(doc, 1);
        JsonNode run = run(code, "{\"tenant\":\"9\"}");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        String runId = run.path("runId").asText();
        api.verify(postRequestedFor(urlEqualTo("/h"))
                .withHeader("X-Correlation-Id", equalTo("corr-4-3"))
                .withHeader("X-Apiorch-Run-Id", equalTo(runId))
                .withHeader("X-Apiorch-Step-Id", equalTo("api1"))
                .withHeader("Idempotency-Key", equalTo(runId + ":api1:1"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-Tenant", equalTo("t-9"))
                .withoutHeader("X-Empty"));
        assertThat(run.at("/output").has("api1")).as("204 gives a null body").isTrue();
        assertThat(run.at("/output/api1").isNull()).isTrue();
    }

    @Test
    void successCheckAndHttpErrorsFailTheRunAndCancelTheRest() throws Exception {
        api.stubFor(post("/ok").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"result\":\"ERROR\"}")));
        api.stubFor(post("/down").willReturn(aResponse().withStatus(503).withBody("busy")));
        String code = PublishedFixtures.uniqueCode("CHK");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/ok").put("successWhen", "body.result != 'ERROR'");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/ok");
        publish(doc, 1);
        JsonNode checked = run(code, "{}");
        assertThat(checked.path("status").asText()).isEqualTo("FAILED");
        assertThat(checked.at("/errors/0/code").asText()).isEqualTo("SUCCESS_CHECK_FAILED");
        assertThat(checked.path("steps").findValuesAsText("status")).containsExactly("FAILED", "CANCELLED");

        String code2 = PublishedFixtures.uniqueCode("CHK");
        ObjectNode doc2 = HttpPipelines.pipeline(code2);
        HttpPipelines.step(doc2, "api1", endpoint, "POST", "/down");
        HttpPipelines.step(doc2, "api2", endpoint, "POST", "/ok");
        publish(doc2, 1);
        JsonNode down = run(code2, "{}");
        assertThat(down.path("status").asText()).isEqualTo("FAILED");
        assertThat(down.at("/errors/0/code").asText()).isEqualTo("HTTP_ERROR");
        assertThat(down.at("/errors/0/httpStatus").asInt()).isEqualTo(503);
    }

    @Test
    void runtimeExpressionErrorAndPadCapFailWithExpressionError() throws Exception {
        api.stubFor(post("/x").willReturn(aResponse().withStatus(200)));
        for (String body : List.of("{ 'n': $number('abc') }", "{ 'p': $pad('x', 50000000) }")) {
            api.resetRequests();
            String code = PublishedFixtures.uniqueCode("EXPR");
            ObjectNode doc = HttpPipelines.pipeline(code);
            HttpPipelines.step(doc, "api1", endpoint, "POST", "/x").put("body", body);
            publish(doc, 1);
            long start = System.nanoTime();
            JsonNode run = run(code, "{}");
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3000);
            assertThat(run.at("/errors/0/code").asText()).as(body).isEqualTo("EXPRESSION_ERROR");
            assertThat(run.at("/errors/0/message").asText()).startsWith("body:");
            assertThat(requestPaths()).isEmpty();
        }
    }

    @Test
    void largeIntegersPassThroughExactly() throws Exception {
        api.stubFor(post("/id").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 12345678901234567, \"sid\": \"12345678901234567\"}")));
        api.stubFor(post("/use").willReturn(aResponse().withStatus(200)));
        String code = PublishedFixtures.uniqueCode("NUM");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/id").put("body", "{}");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/use").put("body",
                "{ 'id': steps.api1.body.id, 'sum': steps.api1.body.id + 0, 'sid': steps.api1.body.sid }");
        publish(doc, 1);
        assertThat(run(code, "{}").path("status").asText()).isEqualTo("COMPLETED");
        String sent = api.getAllServeEvents().stream().filter(e -> e.getRequest().getUrl().equals("/use"))
                .findFirst().orElseThrow().getRequest().getBodyAsString();
        assertThat(sent).contains("\"id\":12345678901234567").contains("\"sid\":\"12345678901234567\"")
                .contains("\"sum\":12345678901234568");
    }
}
