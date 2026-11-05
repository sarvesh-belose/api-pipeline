package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.security.Roles;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Drafts;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-3.3 AC1, AC2, AC5 (no-changes), AC6 and AC8 over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PublishApiIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() throws Exception {
        http = new Http(port);
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
    }

    private static ObjectNode doc(ObjectNode d, String code) {
        d.put("code", code);
        return d;
    }

    @Test
    void publishCreatesVersionAndDeletesTheDraft() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.save(http, doc(TestPipelines.onboardV1(), code));
        HttpResponse<String> res = Drafts.publish(http, code, "Move API5 before API4 per business");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        assertThat(res.headers().firstValue("Location").orElseThrow())
                .endsWith("/api/v1/admin/pipelines/" + code + "/versions/1");
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("checksum").asText()).hasSize(64);
        assertThat(body.path("publishedAt").isTextual()).isTrue();
        assertThat(body.path("publishedBy").asText()).isEqualTo(TestJwt.ALL_ROLES_SUBJECT);
        assertThat(body.path("comment").asText()).isEqualTo("Move API5 before API4 per business");
        assertThat(body.path("executionPlan").toString())
                .isEqualTo("[[\"api1\"],[\"api2\"],[\"api3\"],[\"api4\"],[\"api5\"]]");
        assertThat(body.has("definition")).isFalse();
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/draft").statusCode()).isEqualTo(404);
    }

    @Test
    void invalidDraftIsRejectedAndNothingStored() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.save(http, doc(TestPipelines.onboardUnsafeReorder(), code));
        HttpResponse<String> res = Drafts.publish(http, code, null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(422);
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("type").asText()).isEqualTo("urn:apiorch:problem:definition-invalid");
        assertThat(body.path("errors").get(0).path("code").asText()).isNotBlank();
        assertThat(json.readTree(http.get("/api/v1/admin/pipelines/" + code + "/versions").body())).isEmpty();
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/draft").statusCode()).isEqualTo(200);
    }

    @Test
    void unchangedDraftIsNoChangesEvenWithKeysReordered() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        assertThat(Drafts.savePublished(http, doc(TestPipelines.onboardV1(), code))).isEqualTo(1);
        ObjectNode original = doc(TestPipelines.onboardV1(), code);
        ObjectNode same = json.createObjectNode();
        same.set("steps", original.get("steps"));
        same.put("code", code);
        same.put("dslVersion", 1);
        Drafts.save(http, same);
        HttpResponse<String> res = Drafts.publish(http, code, null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(409);
        assertThat(json.readTree(res.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:no-changes");
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/draft").statusCode()).isEqualTo(200);
    }

    @Test
    void reorderIsAChangeAndVersionsAreListedNewestFirst() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.savePublished(http, doc(TestPipelines.onboardV1(), code));
        assertThat(Drafts.savePublished(http, doc(TestPipelines.onboardReordered(), code))).isEqualTo(2);

        HttpResponse<String> listed = http.as(TestJwt.token("ops-user", Roles.OPS))
                .get("/api/v1/admin/pipelines/" + code + "/versions");
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        JsonNode versions = json.readTree(listed.body());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).path("version").asInt()).isEqualTo(1);
        assertThat(versions.get(0).path("checksum").asText()).isNotEqualTo(versions.get(1).path("checksum").asText());
        assertThat(versions.get(0).has("sourceVersion")).isTrue();
        assertThat(versions.get(0).has("sourceChecksum")).isTrue();
        assertThat(versions.get(0).has("comment")).isTrue();

        JsonNode latest = json.readTree(http.get("/api/v1/admin/pipelines/" + code + "/versions/latest").body());
        assertThat(latest.path("version").asInt()).isEqualTo(2);
        assertThat(latest.at("/definition/steps/2/id").asText()).isEqualTo("api5");
        JsonNode first = json.readTree(http.get("/api/v1/admin/pipelines/" + code + "/versions/1").body());
        assertThat(first.at("/definition/steps/2/id").asText()).isEqualTo("api3");
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/versions/9").statusCode()).isEqualTo(404);
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/versions/x").statusCode()).isEqualTo(404);
    }

    @Test
    void missingDraftOrPipelineIsNotFound() throws Exception {
        assertThat(Drafts.publish(http, "NO_SUCH_PIPE", null).statusCode()).isEqualTo(404);
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.savePublished(http, doc(TestPipelines.onboardV1(), code));
        Thread.sleep(5);
        HttpResponse<String> again = Drafts.publish(http, code, null);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(json.readTree(again.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:not-found");
        assertThat(http.get("/api/v1/admin/pipelines/NO_SUCH_PIPE/versions").statusCode()).isEqualTo(404);
        assertThat(http.get("/api/v1/admin/pipelines/NO_SUCH_PIPE/versions/latest").statusCode()).isEqualTo(404);
    }

    @Test
    void latestWithoutAnyVersionIsNoPublishedVersion() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.save(http, doc(TestPipelines.onboardV1(), code));
        HttpResponse<String> res = http.get("/api/v1/admin/pipelines/" + code + "/versions/latest");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(json.readTree(res.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:no-published-version");
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/versions/0").statusCode()).isEqualTo(404);
    }

    @Test
    void longCommentIsRejected() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.save(http, doc(TestPipelines.onboardV1(), code));
        HttpResponse<String> res = Drafts.publish(http, code, "x".repeat(501));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(400);
        assertThat(http.get("/api/v1/admin/pipelines/" + code + "/draft").statusCode()).isEqualTo(200);
    }

    @Test
    void runWithoutAPublishedVersionIs404() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.save(http, doc(TestPipelines.onboardV1(), code));
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(json.readTree(res.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:no-published-version");
    }

    @Test
    void publishNeedsTheAdminRole() throws Exception {
        String code = PublishedFixtures.uniqueCode("PUB");
        Drafts.save(http, doc(TestPipelines.onboardV1(), code));
        assertThat(http.as(TestJwt.token("ops-user", Roles.OPS))
                .postJson("/api/v1/admin/pipelines/" + code + "/publish", "{}").statusCode()).isEqualTo(403);
    }
}
