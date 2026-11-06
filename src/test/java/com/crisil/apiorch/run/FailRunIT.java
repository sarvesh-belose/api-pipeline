package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/**
 * US-4.6 AC1 and AC6 (sequential; the parallel in-flight branch comes with US-4.4): a {@code FAIL_RUN} failure
 * persists the stop with the step, cancels what never started, and the caller still gets the partial output.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class FailRunIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    @Test
    void failRunStopsCancelsAndReturnsPartialOutput() throws Exception {
        ObjectNode doc = TestPipelines.sequential("FAILRUN", "api1", "api2", "api3", "api4", "api5");
        String code = PublishedFixtures.uniqueCode("FAILRUN");
        doc.put("code", code);
        doc.put("output", "{ 'customerId': steps.api1.body.stepId, 'kyc': steps.api2.body.stepId, "
                + "'score': steps.api3.body.score }");
        PublishedFixtures.publish(jdbc, doc, 1);
        stub.on("api3", StepOutcome.failed(500, "HTTP_ERROR", "HTTP 500", 3, 40));

        HttpResponse<String> res = new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync",
                "{\"name\":\"Asha\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Apiorch-Run-Status")).contains("FAILED");
        JsonNode run = json.readTree(res.body());
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("outputFinal").asBoolean()).isTrue();
        assertThat(run.path("output")).isEqualTo(json.readTree("{\"customerId\":\"api1\",\"kyc\":\"api2\"}"));
        assertThat(run.path("steps").findValuesAsText("status"))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "FAILED", "CANCELLED", "CANCELLED");
        assertThat(run.path("errors")).hasSize(3);
        assertThat(run.at("/errors/0")).isEqualTo(json.readTree(
                "{\"stepId\":\"api3\",\"code\":\"HTTP_ERROR\",\"message\":\"HTTP 500\",\"httpStatus\":500}"));
        assertThat(run.at("/errors/1")).isEqualTo(json.readTree("{\"stepId\":\"api4\",\"code\":\"CANCELLED\"}"));
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2", "api3");

        UUID runId = UUID.fromString(run.path("runId").asText());
        Map<String, Object> row = jdbc.sql("SELECT stop_reason, error_code, error_step_id FROM pipeline_run WHERE id = :id")
                .param("id", runId).query().singleRow();
        assertThat(row).containsEntry("stop_reason", "FAIL_RUN").containsEntry("error_code", "STEP_FAILED")
                .containsEntry("error_step_id", "api3");
        assertThat(jdbc.sql("SELECT event_type FROM run_event WHERE run_id = :id ORDER BY seq").param("id", runId)
                .query(String.class).list()).contains("STEP_FAILED", "RUN_FAILED").doesNotContain("RUN_COMPLETED");
    }
}
