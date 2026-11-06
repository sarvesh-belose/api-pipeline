package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-2.2 AC4 at step level (left over from US-4.3): a cached OAuth2 token rejected with {@code 401} is refreshed
 * once and the call repeated; the step stores one {@code step_attempt} row (the final exchange) and one
 * {@code TOKEN_REFRESHED} event. A second {@code 401} fails the step.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TokenRefreshSingleAttemptIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension idp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static Path secretsDir;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) throws Exception {
        secretsDir = Files.createTempDirectory("apiorch-secrets");
        Files.writeString(secretsDir.resolve("client-secret"), "s3cret");
        registry.add("apiorch.secrets.file-dir", secretsDir::toString);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
        api.resetAll();
        idp.resetAll();
        idp.stubFor(post("/token").inScenario("tokens").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(token("t1")).willSetStateTo("second"));
        idp.stubFor(post("/token").inScenario("tokens").whenScenarioStateIs("second").willReturn(token("t2")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder token(String value) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + value + "\",\"expires_in\":600}");
    }

    private String pipeline() throws Exception {
        String name = "oauth-" + UUID.randomUUID().toString().substring(0, 8);
        String ref = "file:" + secretsDir.resolve("client-secret").toString().replace("\\", "/");
        HttpResponse<String> created = http.postJson("/api/v1/admin/endpoints", """
                {"name":"%s","baseUrl":"http://localhost:%d","connectTimeoutMs":2000,"readTimeoutMs":5000,
                 "auth":{"type":"OAUTH2_CLIENT_CREDENTIALS","tokenUrl":"http://localhost:%d/token",
                         "clientId":"apiorch","clientSecretRef":"%s"}}"""
                .formatted(name, api.getPort(), idp.getPort(), ref));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String code = PublishedFixtures.uniqueCode("OAUTH");
        ObjectNode doc = testsupport.engine.HttpPipelines.pipeline(code);
        testsupport.engine.HttpPipelines.step(doc, "api1", name, "POST", "/accounts").put("body", "{ 'a': 1 }");
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private JsonNode run(String code) throws Exception {
        return json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").body());
    }

    private List<Map<String, Object>> attempts(JsonNode run) {
        return jdbc.sql("SELECT outcome, response_status FROM step_attempt WHERE run_id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query().listOfRows();
    }

    private List<String> events(JsonNode run) {
        return jdbc.sql("SELECT event_type FROM run_event WHERE run_id = :id ORDER BY seq")
                .param("id", UUID.fromString(run.path("runId").asText())).query(String.class).list();
    }

    @Test
    void aRejectedTokenIsRefreshedOnceAndRecordedAsOneAttempt() throws Exception {
        api.stubFor(post("/accounts").withHeader("Authorization", equalTo("Bearer t1"))
                .willReturn(aResponse().withStatus(401)));
        api.stubFor(post("/accounts").withHeader("Authorization", equalTo("Bearer t2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));
        JsonNode run = run(pipeline());

        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        api.verify(2, postRequestedFor(urlEqualTo("/accounts")));
        idp.verify(2, postRequestedFor(urlEqualTo("/token")));
        List<Map<String, Object>> rows = attempts(run);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("outcome", "SUCCESS").containsEntry("response_status", 200);
        assertThat(events(run).stream().filter("TOKEN_REFRESHED"::equals)).hasSize(1);
    }

    @Test
    void aSecond401FailsTheStep() throws Exception {
        api.stubFor(post("/accounts").willReturn(aResponse().withStatus(401)));
        JsonNode run = run(pipeline());

        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        api.verify(2, postRequestedFor(urlEqualTo("/accounts")));
        List<Map<String, Object>> rows = attempts(run);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("outcome")).isEqualTo("AUTH_ERROR");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo(rows.get(0).get("outcome"));
    }
}
