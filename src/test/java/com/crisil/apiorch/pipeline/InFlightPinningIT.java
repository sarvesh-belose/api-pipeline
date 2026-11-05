package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
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
import testsupport.web.Drafts;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-3.3 AC4: a run paused on v1 when v2 is published finishes with v1's steps, in v1 order, and reports v1. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class InFlightPinningIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    @Test
    void runInFlightKeepsItsVersion() throws Exception {
        Http http = new Http(port);
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
        String code = PublishedFixtures.uniqueCode("PIN");
        ObjectNode v1 = TestPipelines.onboardV1();
        v1.put("code", code);
        Drafts.savePublished(http, v1);

        stub.reset();
        stub.hold("api2");
        HttpResponse<String> started = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String runId = json.readTree(started.body()).path("runId").asText();
        await().atMost(Duration.ofSeconds(10)).until(() -> stub.invokedStepIds().contains("api2"));

        ObjectNode v2 = TestPipelines.onboardReordered();
        v2.put("code", code);
        assertThat(Drafts.savePublished(http, v2)).isEqualTo(2);
        stub.release("api2");

        await().atMost(Duration.ofSeconds(10)).until(() -> json.readTree(http.get("/api/v1/runs/" + runId).body())
                .path("status").asText().equals("COMPLETED"));
        JsonNode run = json.readTree(http.get("/api/v1/runs/" + runId).body());
        assertThat(run.path("version").asInt()).isEqualTo(1);
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2", "api3", "api4", "api5");
    }
}
