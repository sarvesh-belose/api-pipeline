package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/**
 * US-5.4 AC6: with one {@code step-io} thread and no queue, the second of two parallel steps is refused by the pool;
 * it is recorded as a {@code BULKHEAD_FULL} attempt ({@code TaskRejectedException}), retried, and the run completes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apiorch.executor.step-io.size=1", "apiorch.executor.step-io.queue-capacity=0"})
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class ExecutorSaturationIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    @Test
    void aRefusedTaskIsABulkheadFullAttemptAndIsRetried() throws Exception {
        ObjectNode doc = TestPipelines.sequential("SAT", "a", "b");
        String code = PublishedFixtures.uniqueCode("SAT");
        doc.put("code", code);
        doc.withArray("steps").forEach(s -> ((ObjectNode) s).putArray("dependsOn"));
        doc.withArray("steps").forEach(s -> ((ObjectNode) s).set("retry", json.createObjectNode()
                .put("maxAttempts", 5).put("backoff", "FIXED").put("initialMs", 300).put("maxMs", 1000)));
        PublishedFixtures.publish(jdbc, doc, 1);
        stub.on("a", inv -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepOutcome.succeeded(200, JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), 1, 500);
        });

        JsonNode run = json.readTree(new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}")
                .body());
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        Map<String, Object> rejected = jdbc.sql("SELECT step_id, outcome, error_class FROM step_attempt "
                + "WHERE run_id = :id AND outcome = 'BULKHEAD_FULL'")
                .param("id", UUID.fromString(run.path("runId").asText())).query().listOfRows().get(0);
        assertThat(rejected).containsEntry("error_class", "TaskRejectedException");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = 'RETRY_SCHEDULED'")
                .param("id", UUID.fromString(run.path("runId").asText())).query(Long.class).single()).isPositive();
    }
}
