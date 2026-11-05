package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import testsupport.engine.TestPipelines;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-3.2 AC1, AC2, AC9: validate the draft or an ad-hoc body over HTTP, without saving. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ValidateApiIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() throws Exception {
        http = new Http(port);
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
    }

    private static String code() {
        return "V_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static ObjectNode withCode(ObjectNode doc, String code) {
        doc.put("code", code);
        return doc;
    }

    private String wrap(JsonNode definition, boolean withCode, String code) throws Exception {
        ObjectNode body = json.createObjectNode();
        if (withCode) {
            body.put("code", code);
        }
        body.set("definition", definition);
        return json.writeValueAsString(body);
    }

    private JsonNode validate(String code, String body, String query) throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/admin/pipelines/" + code + "/validate" + query, body);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    @Test
    void draftReorderIsValidWithPlanAndDescription() throws Exception {
        String code = code();
        http.postJson("/api/v1/admin/pipelines", wrap(withCode(TestPipelines.onboardReordered(), code), true, code));
        JsonNode r = validate(code, null, "");
        assertThat(r.path("valid").asBoolean()).isTrue();
        assertThat(r.path("checked").asText()).isEqualTo("draft");
        assertThat(r.path("executionPlan").toString()).isEqualTo("[[\"api1\"],[\"api2\"],[\"api5\"],[\"api4\"],[\"api3\"]]");
        assertThat(r.path("planDescription").get(0).asText()).isEqualTo("1. api1 (demo-svc POST /api1)");

        JsonNode detailed = validate(code, null, "?detail=true");
        assertThat(detailed.at("/executionPlan/0/0/id").asText()).isEqualTo("api1");
        assertThat(detailed.at("/executionPlan/0/0/postCompletion").asBoolean()).isFalse();
    }

    @Test
    void bodyIsValidatedWithoutSaving() throws Exception {
        String code = code();
        ObjectNode unsafe = withCode(TestPipelines.onboardReordered(), code);
        ((ObjectNode) unsafe.withArray("steps").get(3)).put("body", "{ 'score': steps.api3.body.score }");
        JsonNode r = validate(code, wrap(unsafe, false, code), "");
        assertThat(r.path("valid").asBoolean()).isFalse();
        assertThat(r.path("checked").asText()).isEqualTo("body");
        assertThat(r.at("/errors/0/code").asText()).isEqualTo("FORWARD_REFERENCE");
        assertThat(r.at("/errors/0/severity").asText()).isEqualTo("ERROR");
        assertThat(r.at("/errors/0/path").asText()).isEqualTo("/steps/3/body");
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/draft").statusCode()).isEqualTo(404);
    }

    @Test
    void structureAndCodeProblemsAreReportedNotRejected() throws Exception {
        String code = code();
        ObjectNode doc = withCode(TestPipelines.onboardV1(), "OTHER_CODE");
        JsonNode mismatch = validate(code, wrap(doc, false, code), "");
        assertThat(mismatch.at("/errors/0/code").asText()).isEqualTo("CODE_MISMATCH");

        ((ObjectNode) doc.withArray("steps").get(0)).put("dependOn", "x");
        JsonNode schema = validate(code, wrap(doc, false, code), "");
        assertThat(schema.path("valid").asBoolean()).isFalse();
        assertThat(schema.at("/errors/0/code").asText()).isEqualTo("SCHEMA_INVALID");
        assertThat(schema.at("/errors/0/path").asText()).isEqualTo("/steps/0/dependOn");
    }

    @Test
    void noDraftAndNoBodyIsPipelineNotFound() throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/admin/pipelines/" + code() + "/validate", null);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("urn:apiorch:problem:pipeline-not-found");
    }
}
