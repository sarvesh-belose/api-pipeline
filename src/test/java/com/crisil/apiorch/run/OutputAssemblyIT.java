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
import com.fasterxml.jackson.databind.node.ObjectNode;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/** US-4.6 AC5, AC7 and AC8: the {@code output} expression, its default, and an output that fails at runtime. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class OutputAssemblyIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private JsonNode run(String output) throws Exception {
        ObjectNode doc = TestPipelines.sequential("OUT", "api1", "api2");
        String code = PublishedFixtures.uniqueCode("OUT");
        doc.put("code", code);
        if (output != null) {
            doc.put("output", output);
        }
        PublishedFixtures.publish(jdbc, doc, 1);
        return json.readTree(new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync",
                "{\"name\":\"Asha\"}").body());
    }

    @Test
    void theOutputExpressionShapesTheResult() throws Exception {
        JsonNode run = run("{ 'customerId': steps.api1.body.stepId, 'kyc': steps.api2.body.stepId }");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("output")).isEqualTo(json.readTree("{\"customerId\":\"api1\",\"kyc\":\"api2\"}"));

        UUID runId = UUID.fromString(run.path("runId").asText());
        Map<String, Object> row = jdbc.sql("SELECT completed_at, ended_at FROM pipeline_run WHERE id = :id")
                .param("id", runId).query().singleRow();
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("ended_at")).isNotNull();
        JsonNode details = json.readTree(jdbc.sql(
                "SELECT details FROM run_event WHERE run_id = :id AND event_type = 'RUN_COMPLETED'")
                .param("id", runId).query(String.class).single());
        assertThat(details.path("steps").asInt()).isEqualTo(2);
        assertThat(details.has("durationMs")).isTrue();
    }

    @Test
    void withoutAnOutputExpressionEachSucceededStepsBodyIsReturned() throws Exception {
        JsonNode run = run(null);
        assertThat(run.path("output")).isEqualTo(json.readTree(
                "{\"api1\":{\"stepId\":\"api1\"},\"api2\":{\"stepId\":\"api2\"}}"));
    }

    @Test
    void aFailingOutputIsCompletedWithErrorsAndNoOutput() throws Exception {
        JsonNode run = run("{ 'v': 1 + 'a' }");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.path("output").isMissingNode() || run.path("output").isNull()).isTrue();
        assertThat(run.path("errors").findValuesAsText("code")).containsExactly("OUTPUT_EXPRESSION_ERROR");
        assertThat(jdbc.sql("SELECT stop_reason FROM pipeline_run WHERE id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query().singleRow())
                .containsEntry("stop_reason", null);
    }
}
