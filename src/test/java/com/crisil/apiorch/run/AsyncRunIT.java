package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.security.Roles;
import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/** US-4.2: the async contract (AC1, AC2), polling with {@code Retry-After} (AC3), ownership (AC4), 404s (AC5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class AsyncRunIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http systemA;
    String code;

    @BeforeEach
    void setUp() {
        systemA = new Http(port).as(TestJwt.token("system-a", Roles.RUN));
        ObjectNode doc = TestPipelines.sequential("ASYNC", "slow", "next");
        code = PublishedFixtures.uniqueCode("ASYNC");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        stub.reset();
    }

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private JsonNode body(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body());
    }

    @Test
    void acceptedAtOnceThenPolledUntilDone() throws Exception {
        stub.on("slow", inv -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepOutcome.succeeded(200, null, json.createObjectNode().put("id", "C-1"), 1, 5000);
        });
        systemA.get("/api/v1/runs/" + UUID.randomUUID());
        long start = System.nanoTime();
        HttpResponse<String> res = systemA.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        JsonNode accepted = body(res);
        String runId = accepted.path("runId").asText();
        assertThat(res.headers().firstValue("Location")).hasValue("/api/v1/runs/" + runId);
        assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.path("pipeline").asText()).isEqualTo(code);
        assertThat(accepted.path("version").asInt()).isEqualTo(1);
        assertThat(accepted.at("/links/self").asText()).isEqualTo("/api/v1/runs/" + runId);
        assertThat(res.headers().firstValue("Preference-Applied")).isEmpty();

        HttpResponse<String> running = systemA.get("/api/v1/runs/" + runId);
        assertThat(running.statusCode()).isEqualTo(200);
        assertThat(body(running).path("status").asText()).isIn("ACCEPTED", "RUNNING");
        assertThat(running.headers().firstValue("Retry-After")).hasValue("2");
        assertThat(body(running).has("endedAt")).isFalse();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .until(() -> body(systemA.get("/api/v1/runs/" + runId)).path("status").asText().equals("COMPLETED"));
        HttpResponse<String> done = systemA.get("/api/v1/runs/" + runId);
        assertThat(done.headers().firstValue("Retry-After")).isEmpty();
        JsonNode resource = body(done);
        assertThat(resource.has("output")).isTrue();
        assertThat(resource.has("errors")).isTrue();
        assertThat(resource.has("endedAt")).isTrue();
        assertThat(resource.path("mode").asText()).isEqualTo("ASYNC");
    }

    @Test
    void preferRespondAsyncActsLikeModeAsync() throws Exception {
        HttpResponse<String> res = systemA.send("POST", "/api/v1/pipelines/" + code + "/runs", "{}", null,
                "Prefer", "respond-async, wait=10");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        assertThat(res.headers().firstValue("Preference-Applied")).hasValue("respond-async");
        assertThat(body(res).path("status").asText()).isEqualTo("ACCEPTED");

        HttpResponse<String> explicit = systemA.send("POST", "/api/v1/pipelines/" + code + "/runs?mode=sync", "{}",
                null, "Prefer", "respond-async");
        assertThat(explicit.statusCode()).as("an explicit mode wins").isEqualTo(200);
        assertThat(explicit.headers().firstValue("Preference-Applied")).isEmpty();
        assertThat(RunController.prefersAsync(null)).isFalse();
        assertThat(RunController.prefersAsync("return=minimal")).isFalse();
    }

    @Test
    void onlyTheCreatorOrOpsCanReadARun() throws Exception {
        HttpResponse<String> res = systemA.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        String runId = body(res).path("runId").asText();
        HttpResponse<String> other = systemA.as(TestJwt.token("system-b", Roles.RUN)).get("/api/v1/runs/" + runId);
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(body(other).path("type").asText()).isEqualTo("urn:apiorch:problem:run-not-found");
        assertThat(systemA.as(TestJwt.token("operator", Roles.OPS)).get("/api/v1/runs/" + runId).statusCode())
                .isEqualTo(200);
        assertThat(systemA.get("/api/v1/runs/" + runId).statusCode()).isEqualTo(200);
    }

    @Test
    void unknownOrMalformedRunIdIsRunNotFound() throws Exception {
        for (String id : new String[] {UUID.randomUUID().toString(), "not-a-uuid", "1-1-1-1-1"}) {
            HttpResponse<String> res = systemA.get("/api/v1/runs/" + id);
            assertThat(res.statusCode()).as(id).isEqualTo(404);
            assertThat(body(res).path("type").asText()).isEqualTo("urn:apiorch:problem:run-not-found");
        }
    }
}
