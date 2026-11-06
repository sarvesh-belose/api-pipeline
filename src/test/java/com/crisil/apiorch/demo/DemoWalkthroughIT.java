package com.crisil.apiorch.demo;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import com.crisil.apiorch.config.LocalSeedRunner;

import testsupport.web.Http;

/**
 * US-8.1 slice A, AC1, AC3, AC4 and AC6: the demo as the walkthrough runs it, against WireMock loaded from the same
 * {@code demo/wiremock} mappings the Compose service uses and the seed pointed at it. The seed publishes
 * {@code DEMO_ONBOARD} v1 once (a second run changes nothing); a run calls api1 → api2 → api3 → api5 and its timeline
 * shows every call; v2's reorder is published with no restart and the next run follows it; the broken reorder is
 * rejected with {@code FORWARD_REFERENCE}; KYC "flaky" is retried to success and "down" opens its circuit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apiorch.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:demo-it;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoWalkthroughIT {

    static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort()
            .usingFilesUnderDirectory("demo/wiremock"));

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void seedAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("apiorch.seed.base-url", () -> "http://localhost:" + WIREMOCK.port());
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired LocalSeedRunner seed;

    private JsonNode run() throws Exception {
        HttpResponse<String> res = new Http(port).postJson("/api/v1/pipelines/DEMO_ONBOARD/runs?mode=sync",
                "{\"name\":\"Asha Rao\",\"pan\":\"ABCDE1234F\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private static List<String> paths() {
        List<ServeEvent> events = new ArrayList<>(WIREMOCK.getAllServeEvents());
        Collections.reverse(events);
        return events.stream().map(e -> e.getRequest().getUrl()).toList();
    }

    private static void scenario(String state) throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + WIREMOCK.port() + "/__admin/scenarios/kyc/state"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"" + state + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    }

    private ObjectNode seedFile(String name) throws Exception {
        try (InputStream in = new ClassPathResource("seed/" + name).getInputStream()) {
            return (ObjectNode) json.readTree(in);
        }
    }

    private HttpResponse<String> saveDraft(ObjectNode definition) throws Exception {
        Http http = new Http(port);
        ObjectNode body = json.createObjectNode();
        body.set("definition", definition);
        String etag = http.get("/api/v1/admin/pipelines/DEMO_ONBOARD/draft").headers().firstValue("ETag").orElse(null);
        return http.putJson("/api/v1/admin/pipelines/DEMO_ONBOARD/draft", body.toString(), etag);
    }

    @Test
    @Order(1)
    void theSeedPublishesV1OnceAndTheRunCallsEachStepInOrder() throws Exception {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM endpoint WHERE name IN ('customer-svc', 'kyc-svc', 'score-svc', "
                + "'notify-svc')").query(Long.class).single()).isEqualTo(4);
        seed.run(null);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM pipeline_definition WHERE code = 'DEMO_ONBOARD' AND version > 0")
                .query(Long.class).single()).isEqualTo(1);

        WIREMOCK.resetRequests();
        JsonNode run = run();
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("version").asInt()).isEqualTo(1);
        assertThat(run.path("output")).isEqualTo(json.readTree(
                "{\"customerId\":\"C-77\",\"kyc\":\"VERIFIED\",\"score\":712}"));
        assertThat(paths()).containsExactly("/customers", "/kyc", "/scores/C-77", "/welcome");

        JsonNode timeline = json.readTree(new Http(port).get("/api/v1/runs/" + run.path("runId").asText()
                + "/timeline").body());
        assertThat(timeline.path("calls").findValuesAsText("type"))
                .containsExactly("INBOUND_REQUEST", "REQUEST", "REQUEST", "REQUEST", "REQUEST", "CALLER_RESPONSE");
    }

    @Test
    @Order(2)
    void theReorderIsPublishedWithNoRestartAndTheBrokenOneIsRejected() throws Exception {
        assertThat(saveDraft(seedFile("DEMO_ONBOARD.v2-reordered.json")).statusCode()).isIn(200, 201);
        HttpResponse<String> published = new Http(port).postJson("/api/v1/admin/pipelines/DEMO_ONBOARD/publish",
                "{\"comment\":\"business reorder: notify before scoring\"}");
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);

        WIREMOCK.resetRequests();
        JsonNode run = run();
        assertThat(run.path("version").asInt()).isEqualTo(2);
        assertThat(paths()).containsExactly("/customers", "/kyc", "/welcome", "/scores/C-77");

        saveDraft(seedFile("DEMO_ONBOARD.invalid-reorder.json"));
        HttpResponse<String> rejected = new Http(port).postJson("/api/v1/admin/pipelines/DEMO_ONBOARD/publish", "{}");
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(rejected.body()).contains("FORWARD_REFERENCE");
    }

    @Test
    @Order(3)
    void aFlakyKycIsRetriedAndADownOneOpensTheCircuit() throws Exception {
        scenario("flaky");
        JsonNode retried = run();
        assertThat(retried.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT attempts FROM step_execution WHERE run_id = :id AND step_id = 'api2'")
                .param("id", UUID.fromString(retried.path("runId").asText())).query(Integer.class).single())
                .isEqualTo(3);

        scenario("down");
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            run();
            return json.readTree(new Http(port).get("/api/v1/admin/endpoints/kyc-svc/circuit").body()).path("state")
                    .asText().equals("OPEN");
        });
        JsonNode rejected = run();
        assertThat(rejected.path("status").asText()).isEqualTo("FAILED");
        assertThat(rejected.at("/errors/0/code").asText()).isEqualTo("CIRCUIT_OPEN");
        scenario("Started");
    }
}
