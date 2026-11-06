package com.crisil.apiorch.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import com.crisil.apiorch.engine.RunContext;
import com.crisil.apiorch.persistence.RunRows;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-5.5 AC1, AC2, AC6 and US-5.1 AC7 on one node: a run left by a dead node (expired lease, or released with a
 * wake-up) is claimed by this node's sweeper and continued from its persisted state. Two-node crash, fencing,
 * concurrent sweep and shutdown tests follow with {@code ClusterTestSupport}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apiorch.recovery.sweep-interval-ms=300", "apiorch.lease.renew-interval-ms=300"})
@ActiveProfiles("test")
class RecoveryIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired RunRows rows;
    @Autowired ObjectMapper json;
    String endpoint;

    @BeforeEach
    void setUp() throws Exception {
        api.resetAll();
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true}")));
        endpoint = HttpPipelines.endpoint(new Http(port), "http://localhost:" + api.getPort());
    }

    private String publish(String api2Method) {
        String code = PublishedFixtures.uniqueCode("RECOVER");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/api1").put("body", "{}");
        ObjectNode api2 = HttpPipelines.step(doc, "api2", endpoint, api2Method, "/api2");
        if (!api2Method.equals("GET")) {
            api2.put("body", "{}");
        }
        HttpPipelines.step(doc, "api3", endpoint, "POST", "/api3").put("body", "{}");
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    /** A run the dead node created and drove up to api2, whose call it never finished. */
    private UUID orphan(String code, boolean started, long runTimeoutMs) {
        UUID id = UUID.randomUUID();
        rows.insertRun(id, code, 1, "ASYNC", "c-recover", "test-admin", "dead-node", -1000);
        for (String step : List.of("api1", "api2", "api3")) {
            rows.insertStep(id, step, "SYNC");
        }
        rows.putContext(id, "input", json.createObjectNode());
        if (started) {
            rows.markRunning(id, runTimeoutMs);
            rows.putContext(id, "api1", RunContext.stepResult(200, json.createObjectNode(),
                    json.createObjectNode().put("id", "C-77")));
            rows.finishStep(id, "api1", com.crisil.apiorch.engine.StepStatus.SUCCEEDED, 1, 200, 5L, null, null, false,
                    null);
            rows.markStepRunning(id, "api2");
        }
        return id;
    }

    private String awaitTerminal(UUID id) {
        await().atMost(Duration.ofSeconds(20)).until(() -> rows.run(id).orElseThrow().status().terminal());
        return rows.run(id).orElseThrow().status().name();
    }

    private List<String> paths() {
        return api.getAllServeEvents().stream().map(e -> e.getRequest().getUrl()).toList();
    }

    private List<String> events(UUID id, String type) {
        return jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = :t ORDER BY seq")
                .param("id", id).param("t", type).query(String.class).list();
    }

    @Test
    void anIdempotentInterruptedStepRunsAgainWithTheSameKey() throws Exception {
        UUID id = orphan(publish("GET"), true, 60000);
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");

        assertThat(paths()).containsExactlyInAnyOrder("/api2", "/api3");
        ServeEvent api2 = api.getAllServeEvents().stream().filter(e -> e.getRequest().getUrl().equals("/api2"))
                .findFirst().orElseThrow();
        assertThat(api2.getRequest().getHeader("Idempotency-Key")).isEqualTo(id + ":api2:1");
        List<String> recovered = events(id, "RUN_RECOVERED");
        assertThat(recovered).hasSize(1);
        JsonNode details = json.readTree(recovered.get(0));
        assertThat(details.path("previousOwner").asText()).isEqualTo("dead-node");
        assertThat(details.path("epoch").asLong()).isEqualTo(2);
        assertThat(rows.run(id).orElseThrow().ownerInstance()).isNull();
    }

    @Test
    void aNonIdempotentInterruptedStepIsUnknownAndNothingIsSent() {
        UUID id = orphan(publish("POST"), true, 60000);
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");

        assertThat(paths()).isEmpty();
        Map<String, Object> run = jdbc.sql("SELECT error_code, error_step_id FROM pipeline_run WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(run).containsEntry("error_code", "STEP_OUTCOME_UNKNOWN").containsEntry("error_step_id", "api2");
        assertThat(rows.steps(id)).extracting(RunRows.Step::stepId, s -> s.status().name()).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("api1", "SUCCEEDED"),
                org.assertj.core.groups.Tuple.tuple("api2", "UNKNOWN"),
                org.assertj.core.groups.Tuple.tuple("api3", "CANCELLED"));
        assertThat(events(id, "STEP_OUTCOME_UNKNOWN")).hasSize(1);
    }

    @Test
    void anOrphanStillAcceptedIsStartedAndRunsToTheEnd() {
        UUID id = orphan(publish("GET"), false, 0);
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        assertThat(paths()).containsExactlyInAnyOrder("/api1", "/api2", "/api3");
    }

    @Test
    void pastTheDeadlineTheInterruptedStepIsCutAndTheRunTimesOut() {
        UUID id = orphan(publish("GET"), true, 1);
        assertThat(awaitTerminal(id)).isEqualTo("TIMED_OUT");
        assertThat(paths()).isEmpty();
        assertThat(rows.steps(id)).filteredOn(s -> s.stepId().equals("api2")).extracting(s -> s.status().name())
                .containsExactly("CANCELLED");
    }

    @Test
    void aRunReleasedWithAWakeUpIsPickedUp() {
        UUID id = orphan(publish("GET"), true, 60000);
        jdbc.sql("UPDATE pipeline_run SET owner_instance = NULL, lease_until = NULL, wakeup_requested = 1 WHERE id = :id")
                .param("id", id).update();
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        assertThat(paths()).containsExactlyInAnyOrder("/api2", "/api3");
    }
}
