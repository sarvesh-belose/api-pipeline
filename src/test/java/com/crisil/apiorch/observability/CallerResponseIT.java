package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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

/**
 * US-7.7 AC2 and AC3: one {@code CALLER_RESPONSE} row per response, and exactly one {@code final = 1} row holding the
 * masked final run resource, whether the caller got {@code 200}, a sync {@code 202} or a caller-async {@code 202}.
 * AC4 needs async steps (R2); AC5 is covered with US-4.7.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class CallerResponseIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private String publish(Integer syncWaitMs) {
        ObjectNode doc = TestPipelines.sequential("EXR", "api1", "api2");
        String code = PublishedFixtures.uniqueCode("EXR");
        doc.put("code", code);
        if (syncWaitMs != null) {
            doc.put("syncWaitMs", syncWaitMs);
        }
        ObjectNode logging = doc.putObject("logging");
        logging.put("capture", "FULL");
        logging.putArray("maskKeys").add("stepId");
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private List<Map<String, Object>> responses(String runId) {
        return jdbc.sql("SELECT * FROM run_exchange WHERE run_id = :id AND kind = 'CALLER_RESPONSE' ORDER BY event_seq")
                .param("id", UUID.fromString(runId)).query().listOfRows();
    }

    static boolean isFinal(Map<String, Object> row) {
        Object value = row.get("final");
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() == 1);
    }

    @Test
    void syncOkIsTheOneFinalRow() throws Exception {
        String code = publish(null);
        HttpResponse<String> res = new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync",
                "{\"name\":\"x\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        String runId = json.readTree(res.body()).path("runId").asText();

        List<Map<String, Object>> rows = responses(runId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("http_status")).isEqualTo(200);
        assertThat(isFinal(rows.get(0))).isTrue();
        JsonNode stored = json.readTree((String) rows.get(0).get("body"));
        assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(stored.at("/output/api2/stepId").asText()).isEqualTo("***");
        assertThat(json.readTree(res.body()).at("/output/api2/stepId").asText()).isEqualTo("api2");
        assertThat(((Number) rows.get(0).get("bytes")).longValue())
                .isEqualTo(res.body().getBytes(StandardCharsets.UTF_8).length);
        assertThat(json.readTree((String) rows.get(0).get("headers")).path("X-Apiorch-Run-Status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void sync202ThenTheFinalResultAtTheTerminalState() throws Exception {
        String code = publish(300);
        stub.hold("api2");
        HttpResponse<String> res = new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync",
                "{\"name\":\"x\"}");
        assertThat(res.statusCode()).isEqualTo(202);
        String runId = json.readTree(res.body()).path("runId").asText();
        stub.release("api2");

        await().atMost(Duration.ofSeconds(10)).until(() -> responses(runId).size() == 2);
        List<Map<String, Object>> rows = responses(runId);
        assertThat(rows.get(0).get("http_status")).isEqualTo(202);
        assertThat(isFinal(rows.get(0))).isFalse();
        assertThat(rows.get(1).get("http_status")).isNull();
        assertThat(isFinal(rows.get(1))).isTrue();
        assertThat(json.readTree((String) rows.get(1).get("body")).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void callerAsync202ThenTheFinalResult() throws Exception {
        String code = publish(null);
        stub.hold("api1");
        HttpResponse<String> res = new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=async",
                "{\"name\":\"x\"}");
        assertThat(res.statusCode()).isEqualTo(202);
        String runId = json.readTree(res.body()).path("runId").asText();
        stub.release("api1");

        await().atMost(Duration.ofSeconds(10)).until(() -> responses(runId).size() == 2);
        List<Map<String, Object>> rows = responses(runId);
        assertThat(rows.get(0).get("http_status")).isEqualTo(202);
        assertThat(isFinal(rows.get(0))).isFalse();
        assertThat(json.readTree((String) rows.get(0).get("body")).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(isFinal(rows.get(1))).isTrue();
    }
}
