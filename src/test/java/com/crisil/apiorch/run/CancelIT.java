package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.security.Roles;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-5.7 AC1, AC2 (before US-5.6), AC4, AC6 and AC7: cancelling a running run lets the in-flight step finish with
 * its real outcome, cancels what never started and ends {@code CANCELLED}, with the actor and reason on record;
 * terminal runs answer {@code 409}; a reason is required and the action needs {@code apiorch.ops}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class CancelIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired RunRows rows;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private String publish() {
        ObjectNode doc = TestPipelines.sequential("CANCEL", "api1", "api2", "api3", "api4");
        String code = PublishedFixtures.uniqueCode("CANCEL");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private Http ops() {
        return new Http(port).as(TestJwt.token("ops-user", Roles.OPS));
    }

    private HttpResponse<String> cancel(Http http, String runId, String body) throws Exception {
        return http.postJson("/api/v1/runs/" + runId + "/cancel", body);
    }

    @Test
    void theInFlightStepKeepsItsRealOutcomeAndTheRunEndsCancelled() throws Exception {
        String code = publish();
        stub.hold("api3");
        String runId = json.readTree(new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}")
                .body()).path("runId").asText();
        await().atMost(Duration.ofSeconds(10)).until(() -> stub.invokedStepIds().contains("api3"));

        HttpResponse<String> accepted = cancel(ops(), runId, "{\"reason\":\"Customer withdrew\",\"compensate\":false}");
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(json.readTree(accepted.body()).path("runId").asText()).isEqualTo(runId);
        stub.release("api3");

        UUID id = UUID.fromString(runId);
        await().atMost(Duration.ofSeconds(10)).until(() -> rows.run(id).orElseThrow().status().terminal());
        JsonNode run = json.readTree(new Http(port).get("/api/v1/runs/" + runId).body());
        assertThat(run.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(run.path("steps").findValuesAsText("status"))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "CANCELLED");
        assertThat(stub.invokedStepIds()).doesNotContain("api4");
        List<String> types = jdbc.sql("SELECT event_type FROM run_event WHERE run_id = :id ORDER BY seq")
                .param("id", id).query(String.class).list();
        // the final caller-response row (US-7.7) follows the terminal event
        assertThat(types).contains("RUN_CANCEL_REQUESTED").endsWith("RUN_CANCELLED", "CALLER_RESPONSE_SENT");
        assertThat(jdbc.sql("SELECT actor FROM run_event WHERE run_id = :id AND event_type = 'RUN_CANCEL_REQUESTED'")
                .param("id", id).query(String.class).single()).isEqualTo("ops-user");
        assertThat(jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = 'RUN_CANCEL_REQUESTED'")
                .param("id", id).query(String.class).single()).contains("Customer withdrew");
        assertThat(jdbc.sql("SELECT wakeup_requested FROM pipeline_run WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isFalse();

        HttpResponse<String> again = cancel(ops(), runId, "{\"reason\":\"again\"}");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("urn:apiorch:problem:illegal-run-state").contains("CANCELLED");
    }

    @Test
    void cancelNeedsAReasonOpsAndNoCompensationYet() throws Exception {
        String code = publish();
        stub.hold("api1");
        String runId = json.readTree(new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}")
                .body()).path("runId").asText();
        assertThat(cancel(ops(), runId, "{}").statusCode()).isEqualTo(400);
        assertThat(cancel(ops(), runId, "{\"reason\":\"x\",\"compensate\":true}").body())
                .contains("compensation not available");
        assertThat(cancel(new Http(port).as(TestJwt.token("system-a", Roles.RUN)), runId, "{\"reason\":\"x\"}")
                .statusCode()).isEqualTo(403);
        assertThat(cancel(ops(), UUID.randomUUID().toString(), "{\"reason\":\"x\"}").statusCode()).isEqualTo(404);
        stub.release("api1");
        await().atMost(Duration.ofSeconds(10)).until(() -> rows.run(UUID.fromString(runId)).orElseThrow().status()
                .terminal());
        assertThat(rows.run(UUID.fromString(runId)).orElseThrow().status().name()).isEqualTo("COMPLETED");
    }
}
