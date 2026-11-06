package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/** US-4.6 AC3 ({@code CONTINUE}) and AC4 ({@code FALLBACK}, including a fallback that itself fails). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class ContinueAndFallbackPolicyIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private String publish(ObjectNode doc) {
        String code = PublishedFixtures.uniqueCode(doc.path("code").asText());
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private static ObjectNode step(ObjectNode doc, String id) {
        for (JsonNode s : doc.path("steps")) {
            if (s.path("id").asText().equals(id)) {
                return (ObjectNode) s;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private JsonNode run(String code) throws Exception {
        return json.readTree(new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync",
                "{\"name\":\"Asha\"}").body());
    }

    private List<String> events(JsonNode run) {
        return jdbc.sql("SELECT event_type FROM run_event WHERE run_id = :id ORDER BY seq")
                .param("id", UUID.fromString(run.path("runId").asText())).query(String.class).list();
    }

    @Test
    void continueToleratesAnOptionalStep() throws Exception {
        ObjectNode doc = TestPipelines.sequential("CONT", "api1", "api2", "api3", "api4", "api5");
        step(doc, "api5").put("onFailure", "CONTINUE");
        String code = publish(doc);
        stub.on("api5", StepOutcome.failed(503, "HTTP_ERROR", "HTTP 503", 1, 5));

        JsonNode run = run(code);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.path("steps").findValuesAsText("status"))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "FAILED");
        assertThat(run.path("errors")).hasSize(1);
        assertThat(run.at("/errors/0/stepId").asText()).isEqualTo("api5");
        assertThat(events(run)).contains("RUN_COMPLETED_WITH_ERRORS");
    }

    @Test
    void continueSkipsDependentsWithDependencyFailed() throws Exception {
        ObjectNode doc = TestPipelines.sequential("CONT", "api1", "api2", "api3");
        step(doc, "api2").put("onFailure", "CONTINUE");
        String code = publish(doc);
        stub.on("api2", StepOutcome.failed(500, "HTTP_ERROR", "HTTP 500", 1, 5));

        JsonNode run = run(code);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.at("/steps/2/status").asText()).isEqualTo("SKIPPED");
        assertThat(run.at("/steps/2/skipReason").asText()).isEqualTo("DEPENDENCY_FAILED");
    }

    @Test
    void fallbackFeedsDependentsAndIsFlagged() throws Exception {
        ObjectNode doc = TestPipelines.sequential("FALLBK", "api1", "api2", "api3", "api4");
        step(doc, "api3").put("onFailure", "FALLBACK")
                .put("fallback", "{ 'score': 0, 'source': 'fallback', 'reason': error.code }");
        String code = publish(doc);
        stub.on("api3", StepOutcome.failed(null, "TIMEOUT", "read timed out", 1, 2000));

        JsonNode run = run(code);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.at("/steps/2/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.at("/steps/2/fallbackApplied").asBoolean()).isTrue();
        assertThat(run.path("errors")).hasSize(1);
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("TIMEOUT");
        assertThat(run.at("/errors/0/fallbackApplied").asBoolean()).isTrue();
        assertThat(run.at("/output/api3")).isEqualTo(json.readTree(
                "{\"score\":0,\"source\":\"fallback\",\"reason\":\"TIMEOUT\"}"));
        assertThat(stub.invocations().get(3).context().at("/steps/api3/body/score").asInt(-1)).isZero();
        assertThat(jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = 'STEP_FALLBACK_APPLIED'")
                .param("id", UUID.fromString(run.path("runId").asText())).query(String.class).single())
                .contains("\"errorCode\":\"TIMEOUT\"");
        assertThat(jdbc.sql("SELECT fallback_applied FROM step_execution WHERE run_id = :id AND step_id = 'api3'")
                .param("id", UUID.fromString(run.path("runId").asText())).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aFailingFallbackStopsTheRunWithExpressionError() throws Exception {
        ObjectNode doc = TestPipelines.sequential("FALLBK", "api1", "api2", "api3");
        step(doc, "api2").put("onFailure", "FALLBACK").put("fallback", "{ 'v': 1 + 'a' }");
        String code = publish(doc);
        stub.on("api2", StepOutcome.failed(null, "TIMEOUT", "read timed out", 1, 2000));

        JsonNode run = run(code);
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/steps/1/status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("EXPRESSION_ERROR");
        assertThat(run.at("/steps/2/status").asText()).isEqualTo("CANCELLED");
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2");
    }
}
