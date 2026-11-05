package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
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

import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-3.1 AC1, AC2, AC4, AC5, AC8: draft create, replace, list, get and delete over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DraftApiIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() throws Exception {
        http = new Http(port);
        for (String name : new String[] {"customer-svc", "kyc-svc", "score-svc", "account-svc", "notify-svc"}) {
            http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name, "https://" + name + ".example.com"));
        }
    }

    private ObjectNode definition(String code) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/dsl/customer-onboard.json")) {
            ObjectNode doc = (ObjectNode) json.readTree(in);
            doc.put("code", code);
            return doc;
        }
    }

    private static String uniqueCode() {
        return "P_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace('-', '_');
    }

    private String createBody(String code, JsonNode definition) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("code", code);
        body.put("description", "test pipeline");
        body.set("definition", definition);
        return json.writeValueAsString(body);
    }

    private String putBody(JsonNode definition) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.set("definition", definition);
        return json.writeValueAsString(body);
    }

    private JsonNode body(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body());
    }

    @Test
    void createStoresTheDraftAndReturnsValidation() throws Exception {
        String code = uniqueCode();
        HttpResponse<String> res = http.postJson("/api/v1/admin/pipelines", createBody(code, definition(code)));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        assertThat(res.headers().firstValue("Location")).hasValue("/api/v1/admin/pipelines/" + code + "/draft");
        assertThat(res.headers().firstValue("ETag")).hasValue("\"0\"");
        JsonNode b = body(res);
        assertThat(b.path("version").asInt()).isZero();
        assertThat(b.path("status").asText()).isEqualTo("DRAFT");
        assertThat(b.path("updatedBy").asText()).isEqualTo("anonymous");
        assertThat(b.at("/validation/valid").asBoolean()).isTrue();
        assertThat(b.at("/validation/warnings")).isEmpty();

        HttpResponse<String> again = http.postJson("/api/v1/admin/pipelines", createBody(code, definition(code)));
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("urn:apiorch:problem:already-exists");
    }

    @Test
    void getReturnsTheDraftExactlyAsSavedWithDslVersion() throws Exception {
        String code = uniqueCode();
        ObjectNode doc = definition(code);
        doc.remove("dslVersion");
        http.postJson("/api/v1/admin/pipelines", createBody(code, doc));
        HttpResponse<String> res = http.get("/api/v1/admin/pipelines/" + code + "/draft");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode stored = body(res).path("definition");
        assertThat(stored.path("dslVersion").asInt()).isEqualTo(1);
        assertThat(stored.fieldNames().next()).isEqualTo("dslVersion");
        assertThat(stored.path("steps").findValuesAsText("id")).containsExactly("api1", "api2", "api3", "api4", "api5");
        assertThat(stored.at("/steps/1/body").asText()).isEqualTo(doc.at("/steps/1/body").asText());
    }

    @Test
    void putCreatesThenReplacesWithIfMatch() throws Exception {
        String code = uniqueCode();
        String path = "/api/v1/admin/pipelines/" + code + "/draft";
        http.postJson("/api/v1/admin/pipelines", createBody(code, definition(code)));
        assertThat(http.delete(path).statusCode()).isEqualTo(204);
        assertThat(http.get(path).statusCode()).isEqualTo(404);

        HttpResponse<String> created = http.putJson(path, putBody(definition(code)), null);
        assertThat(created.statusCode()).isEqualTo(201);

        HttpResponse<String> missing = http.putJson(path, putBody(definition(code)), null);
        assertThat(missing.statusCode()).isEqualTo(428);
        HttpResponse<String> replaced = http.putJson(path, putBody(definition(code)), "\"0\"");
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(replaced.headers().firstValue("ETag")).hasValue("\"1\"");
        HttpResponse<String> stale = http.putJson(path, putBody(definition(code)), "\"0\"");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("urn:apiorch:problem:version-conflict");
    }

    @Test
    void putRulesOnCodes() throws Exception {
        String code = uniqueCode();
        http.postJson("/api/v1/admin/pipelines", createBody(code, definition(code)));
        String path = "/api/v1/admin/pipelines/" + code + "/draft";
        assertThat(http.putJson(path, putBody(definition("OTHER_CODE")), "\"0\"").body())
                .contains("/definition/code");
        assertThat(http.putJson(path, createBody(code, definition(code)), "\"0\"").statusCode()).isEqualTo(400);
        assertThat(http.putJson("/api/v1/admin/pipelines/NOPE_NOPE/draft", putBody(definition("NOPE_NOPE")), null)
                .body()).contains("urn:apiorch:problem:pipeline-not-found");
        assertThat(http.postJson("/api/v1/admin/pipelines", createBody("lower", definition("lower"))).statusCode())
                .isEqualTo(400);
    }

    @Test
    void listShowsDraftAndPublishState() throws Exception {
        String code = uniqueCode();
        http.postJson("/api/v1/admin/pipelines", createBody(code, definition(code)));
        HttpResponse<String> res = http.get("/api/v1/admin/pipelines");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode row = null;
        for (JsonNode r : body(res)) {
            if (r.path("code").asText().equals(code)) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("description").asText()).isEqualTo("test pipeline");
        assertThat(row.path("enabled").asBoolean()).isTrue();
        assertThat(row.path("latestVersion").isNull()).isTrue();
        assertThat(row.path("hasDraft").asBoolean()).isTrue();
        assertThat(row.hasNonNull("draftUpdatedAt")).isTrue();
    }

    @Test
    void structuralErrorsAreRejectedWithPointers() throws Exception {
        String code = uniqueCode();
        ObjectNode doc = definition(code);
        ((ObjectNode) doc.withArray("steps").get(2)).putArray("dependOn").add("api1");
        HttpResponse<String> res = http.postJson("/api/v1/admin/pipelines", createBody(code, doc));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(body(res).path("errors").findValuesAsText("field")).containsExactly("/definition/steps/2/dependOn");
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/draft").statusCode()).isEqualTo(404);
    }
}
