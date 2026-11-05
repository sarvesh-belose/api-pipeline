package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Drafts;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-3.4 AC1, AC3, AC4, AC5: diff over HTTP, rollback as a new version, and its failure cases. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class RollbackIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired StubStepExecutor stub;
    Http http;

    @BeforeEach
    void setUp() throws Exception {
        http = new Http(port);
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
        stub.reset();
    }

    private String threeVersions() throws Exception {
        String code = PublishedFixtures.uniqueCode("ROLL");
        ObjectNode v1 = TestPipelines.onboardV1();
        v1.put("code", code);
        Drafts.savePublished(http, v1);
        ObjectNode v2 = TestPipelines.onboardReordered();
        v2.put("code", code);
        Drafts.savePublished(http, v2);
        ObjectNode v3 = TestPipelines.onboardReordered();
        v3.put("code", code);
        v3.put("runTimeoutMs", 45000);
        Drafts.savePublished(http, v3);
        return code;
    }

    private HttpResponse<String> rollback(String code, int to) throws Exception {
        return http.postJson("/api/v1/admin/pipelines/" + code + "/rollback?to=" + to, "{\"comment\":\"Revert reorder\"}");
    }

    @Test
    void diffOverHttp() throws Exception {
        String code = threeVersions();
        HttpResponse<String> res = http.get("/api/v1/admin/pipelines/" + code + "/versions/2/diff?against=1");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode diff = json.readTree(res.body());
        assertThat(diff.path("orderChanged").asBoolean()).isTrue();
        assertThat(diff.path("fromOrder").toString()).isEqualTo("[\"api1\",\"api2\",\"api3\",\"api4\",\"api5\"]");
        assertThat(diff.path("toOrder").toString()).isEqualTo("[\"api1\",\"api2\",\"api5\",\"api4\",\"api3\"]");
        assertThat(diff.path("toPlan").toString()).isEqualTo("[[\"api1\"],[\"api2\"],[\"api5\"],[\"api4\"],[\"api3\"]]");
        assertThat(diff.path("addedSteps")).isEmpty();
        assertThat(diff.path("removedSteps")).isEmpty();

        JsonNode settings = json.readTree(http.get("/api/v1/admin/pipelines/" + code
                + "/versions/3/diff?against=2").body());
        assertThat(settings.path("orderChanged").asBoolean()).isFalse();
        assertThat(settings.at("/changedSettings/0/field").asText()).isEqualTo("runTimeoutMs");
        assertThat(settings.at("/changedSettings/0/to").asInt()).isEqualTo(45000);
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/versions/9/diff?against=1").statusCode())
                .isEqualTo(404);
    }

    @Test
    void rollbackRepublishesTheOldVersionAndNewRunsUseIt() throws Exception {
        String code = threeVersions();
        Drafts.save(http, doc(TestPipelines.onboardV1(), code));
        HttpResponse<String> res = rollback(code, 1);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("version").asInt()).isEqualTo(4);
        assertThat(body.path("publishedBy").asText()).isEqualTo(TestJwt.ALL_ROLES_SUBJECT);
        assertThat(body.path("comment").asText()).isEqualTo("Revert reorder");

        JsonNode versions = json.readTree(http.get("/api/v1/admin/pipelines/" + code + "/versions").body());
        assertThat(versions.get(0).path("version").asInt()).isEqualTo(4);
        assertThat(versions.get(0).path("sourceVersion").asInt()).isEqualTo(1);
        assertThat(versions.get(0).path("sourceChecksum").asText()).isEqualTo(versions.get(3).path("checksum").asText());
        assertThat(versions.get(0).path("checksum").asText()).isEqualTo(body.path("checksum").asText());
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/draft").statusCode()).as("draft untouched")
                .isEqualTo(200);

        HttpResponse<String> run = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}");
        assertThat(json.readTree(run.body()).path("version").asInt()).isEqualTo(4);
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2", "api3", "api4", "api5");
    }

    @Test
    void rollbackToAVersionWhoseEndpointIsGoneIsRejected() throws Exception {
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal("old-svc", "https://old.example.com"));
        String code = PublishedFixtures.uniqueCode("ROLL");
        ObjectNode v1 = TestPipelines.onboardV1();
        v1.put("code", code);
        ((ObjectNode) v1.withArray("steps").get(4)).put("endpoint", "old-svc");
        Drafts.savePublished(http, v1);
        ObjectNode v2 = TestPipelines.onboardReordered();
        v2.put("code", code);
        Drafts.savePublished(http, v2);
        HttpResponse<String> deleted = http.delete("/api/v1/admin/endpoints/old-svc");
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);

        HttpResponse<String> res = rollback(code, 1);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(422);
        assertThat(json.readTree(res.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:definition-invalid");
        assertThat(json.readTree(res.body()).path("errors")).isNotEmpty();
        assertThat(json.readTree(http.get("/api/v1/admin/pipelines/" + code + "/versions").body())).hasSize(2);
    }

    @Test
    void rollbackToLatestOrMissingVersion() throws Exception {
        String code = threeVersions();
        HttpResponse<String> latest = rollback(code, 3);
        assertThat(latest.statusCode()).isEqualTo(409);
        assertThat(json.readTree(latest.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:no-changes");
        assertThat(rollback(code, 7).statusCode()).isEqualTo(404);
        assertThat(rollback(code, 0).statusCode()).isEqualTo(404);
        assertThat(rollback("NO_SUCH_PIPE", 1).statusCode()).isEqualTo(404);
        assertThat(json.readTree(http.get("/api/v1/admin/pipelines/" + code + "/versions").body())).hasSize(3);
    }

    private static ObjectNode doc(ObjectNode d, String code) {
        d.put("code", code);
        return d;
    }
}
