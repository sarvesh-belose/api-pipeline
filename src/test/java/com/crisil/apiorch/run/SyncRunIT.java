package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/** US-4.1 AC1–AC7 (and US-7.1 AC1) end to end on the stub executor. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class SyncRunIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http caller;

    @BeforeEach
    void setUp() {
        stub.reset();
        caller = new Http(port).as(TestJwt.token("crm-system", Roles.RUN));
    }

    private String published(ObjectNode doc, int version) {
        String code = PublishedFixtures.uniqueCode("RUN");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, version);
        return code;
    }

    private HttpResponse<String> start(String code, String body) throws Exception {
        return caller.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", body);
    }

    private JsonNode body(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body());
    }

    private int runRows(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM pipeline_run WHERE pipeline_code = :c").param("c", code)
                .query(Integer.class).single();
    }

    @Test
    void completedRunIs200WithTheResource() throws Exception {
        String code = published(TestPipelines.onboardV1(), 3);
        HttpResponse<String> res = start(code, "{\"name\":\"Asha\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode run = body(res);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("version").asInt()).isEqualTo(3);
        assertThat(run.path("outputFinal").asBoolean()).isTrue();
        assertThat(run.path("output").path("api1").path("stepId").asText()).isEqualTo("api1");
        assertThat(run.path("steps").findValuesAsText("id")).containsExactly("api1", "api2", "api3", "api4", "api5");
        assertThat(run.path("steps").findValuesAsText("status")).containsOnly("SUCCEEDED");
        assertThat(run.has("durationMs")).isTrue();
        assertThat(res.headers().firstValue("X-Apiorch-Run-Status")).hasValue("COMPLETED");
        assertThat(res.headers().firstValue("X-Apiorch-Run-Id")).hasValue(run.path("runId").asText());
        assertThat(res.headers().firstValue("X-Correlation-Id")).isPresent();
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2", "api3", "api4", "api5");
        assertThat(stub.invocations().get(1).context().at("/steps/api1/body/stepId").asText()).isEqualTo("api1");
        assertThat(stub.invocations().get(0).context().at("/input/name").asText()).isEqualTo("Asha");
    }

    @Test
    void persistedRowsAndEvents() throws Exception {
        String code = published(TestPipelines.onboardV1(), 1);
        JsonNode run = body(start(code, "{\"name\":\"Asha\"}"));
        UUID runId = UUID.fromString(run.path("runId").asText());
        Map<String, Object> row = jdbc.sql("SELECT * FROM pipeline_run WHERE id = :id").param("id", runId).query()
                .singleRow();
        assertThat(row).containsEntry("created_by", "crm-system").containsEntry("mode", "SYNC")
                .containsEntry("pipeline_version", 1);
        assertThat(((Number) row.get("lease_epoch")).longValue()).isEqualTo(1);
        assertThat(row.get("owner_instance")).isNull();
        List<String> events = jdbc.sql("SELECT event_type FROM run_event WHERE run_id = :id ORDER BY seq")
                .param("id", runId).query(String.class).list();
        assertThat(events.subList(0, 4)).containsExactly("RUN_ACCEPTED", "INBOUND_REQUEST_RECEIVED", "RUN_STARTED",
                "STEP_SCHEDULED");
        assertThat(events).endsWith("RUN_COMPLETED", "CALLER_RESPONSE_SENT").contains("STEP_SUCCEEDED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_context WHERE run_id = :id AND entry_key = 'input'")
                .param("id", runId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void slowRunIs202AndKeepsGoing() throws Exception {
        ObjectNode doc = TestPipelines.onboardV1();
        doc.put("syncWaitMs", 300);
        String code = published(doc, 1);
        stub.hold("api2");
        HttpResponse<String> res = start(code, "{\"name\":\"Asha\"}");
        assertThat(res.statusCode()).isEqualTo(202);
        String runId = body(res).path("runId").asText();
        assertThat(res.headers().firstValue("Location")).hasValue("/api/v1/runs/" + runId);
        assertThat(res.headers().firstValue("Retry-After")).hasValue("2");
        assertThat(body(res).path("status").asText()).isEqualTo("RUNNING");
        stub.release("api2");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(body(caller.get("/api/v1/runs/" + runId)).path("status").asText()).isEqualTo("COMPLETED"));
    }

    @Test
    void failedRunIs200WithErrors() throws Exception {
        String code = published(TestPipelines.onboardV1(), 1);
        stub.on("api3", StepOutcome.failed(503, "HTTP_ERROR", "score service down", 1, 12));
        HttpResponse<String> res = start(code, "{\"name\":\"Asha\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode run = body(res);
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(res.headers().firstValue("X-Apiorch-Run-Status")).hasValue("FAILED");
        assertThat(run.at("/errors/0/stepId").asText()).isEqualTo("api3");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("HTTP_ERROR");
        assertThat(run.at("/errors/0/httpStatus").asInt()).isEqualTo(503);
        assertThat(run.path("steps").findValuesAsText("status")).containsExactly("SUCCEEDED", "SUCCEEDED", "FAILED",
                "CANCELLED", "CANCELLED");
        assertThat(stub.invokedStepIds()).doesNotContain("api4", "api5");
    }

    @Test
    void continuePolicyEndsCompletedWithErrors() throws Exception {
        ObjectNode doc = TestPipelines.onboardV1();
        ((ObjectNode) doc.withArray("steps").get(4)).put("onFailure", "CONTINUE");
        String code = published(doc, 1);
        stub.on("api5", StepOutcome.failed(500, "HTTP_ERROR", "notify failed", 1, 3));
        JsonNode run = body(start(code, "{\"name\":\"Asha\"}"));
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.at("/errors/0/stepId").asText()).isEqualTo("api5");
    }

    @Test
    void inputIsCheckedBeforeAnyRow() throws Exception {
        ObjectNode doc = TestPipelines.onboardV1();
        doc.putObject("input").put("validWhen", "$length(pan) = 10").putArray("required").add("name").add("pan");
        String code = published(doc, 1);
        HttpResponse<String> missing = start(code, "{\"name\":\"Asha\"}");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("missing: pan");
        HttpResponse<String> invalid = start(code, "{\"name\":\"Asha\",\"pan\":\"ABCDE1234\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("validWhen failed");
        assertThat(start(code, "[1,2]").statusCode()).isEqualTo(400);
        assertThat(runRows(code)).isZero();
        assertThat(start(code, "{\"name\":\"Asha\",\"pan\":\"ABCDE1234F\"}").statusCode()).isEqualTo(200);
    }

    @Test
    void unknownPipelineNoVersionAndOverrideRules() throws Exception {
        assertThat(start("NOT_THERE", "{}").body()).contains("urn:apiorch:problem:pipeline-not-found");
        String unpublished = PublishedFixtures.uniqueCode("DRAFTONLY");
        jdbc.sql("INSERT INTO pipeline (code, created_by, updated_by) VALUES (:c, 'a', 'a')").param("c", unpublished)
                .update();
        assertThat(start(unpublished, "{}").body()).contains("urn:apiorch:problem:no-published-version");

        ObjectNode doc = TestPipelines.onboardV1();
        String code = published(doc, 1);
        PublishedFixtures.publish(jdbc, doc, 2);
        assertThat(caller.postJson("/api/v1/pipelines/" + code + "/runs?version=1", "{}").statusCode()).isEqualTo(403);
        Http admin = new Http(port).as(TestJwt.token("admin", Roles.RUN, Roles.ADMIN));
        HttpResponse<String> pinned = admin.postJson("/api/v1/pipelines/" + code + "/runs?version=1", "{}");
        assertThat(body(pinned).path("version").asInt()).isEqualTo(1);
        assertThat(admin.postJson("/api/v1/pipelines/" + code + "/runs?version=9", "{}").body())
                .contains("urn:apiorch:problem:no-published-version");
        assertThat(body(start(code, "{}")).path("version").asInt()).isEqualTo(2);
        assertThat(caller.postJson("/api/v1/pipelines/" + code + "/runs?mode=other", "{}").statusCode()).isEqualTo(400);
    }

    @Test
    void asyncModeReturns202Immediately() throws Exception {
        String code = published(TestPipelines.onboardV1(), 1);
        stub.hold("api1");
        HttpResponse<String> res = caller.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        assertThat(res.statusCode()).isEqualTo(202);
        stub.release("api1");
        String runId = body(res).path("runId").asText();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(body(caller.get("/api/v1/runs/" + runId)).path("status").asText()).isEqualTo("COMPLETED"));
        assertThat(caller.get("/api/v1/runs/" + UUID.randomUUID()).body()).contains("urn:apiorch:problem:run-not-found");
    }
}
