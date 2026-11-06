package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
 * US-7.6 AC1 and AC4 end to end: the raw {@code run_context} column is {@code v1:t1:…} with {@code key_id = t1}
 * and holds no plaintext, while mapping and the run output work; a tampered entry fails the run with
 * {@code CONTEXT_DECRYPTION_FAILED} instead of being used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class EncryptedContextIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private String publish() {
        ObjectNode doc = TestPipelines.sequential("ENC", "api1", "api2");
        String code = PublishedFixtures.uniqueCode("ENC");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    @Test
    void contextIsEncryptedAtRestAndReadableThroughTheApplication() throws Exception {
        stub.reset();
        String code = publish();
        JsonNode run = json.readTree(new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync",
                "{\"name\":\"SECRET-NAME-77\"}").body());
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.at("/output/api2/stepId").asText()).isEqualTo("api2");
        assertThat(stub.invocations().get(1).context().at("/input/name").asText()).isEqualTo("SECRET-NAME-77");

        List<Map<String, Object>> rows = jdbc.sql("SELECT entry_key, content, key_id FROM run_context WHERE run_id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("entry_key")).contains("input", "api1", "api2", "output");
        for (Map<String, Object> row : rows) {
            String raw = new String((byte[]) row.get("content"), StandardCharsets.UTF_8);
            assertThat(raw).startsWith("v1:t1:").doesNotContain("SECRET-NAME-77").doesNotContain("{");
            assertThat(row.get("key_id")).isEqualTo("t1");
        }
    }

    @Test
    void tamperedEntryFailsTheRun() throws Exception {
        stub.reset();
        stub.hold("api1");
        String code = publish();
        Http http = new Http(port);
        String runId = json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=async",
                "{\"name\":\"x\"}").body()).path("runId").asText();
        await().atMost(Duration.ofSeconds(10)).until(() -> stub.invokedStepIds().contains("api1"));

        UUID id = UUID.fromString(runId);
        String stored = new String(jdbc.sql("SELECT content FROM run_context WHERE run_id = :id AND entry_key = 'input'")
                .param("id", id).query(byte[].class).single(), StandardCharsets.UTF_8);
        byte[] sealed = Base64.getDecoder().decode(stored.substring("v1:t1:".length()));
        sealed[sealed.length - 1] ^= 1;
        jdbc.sql("UPDATE run_context SET content = :c WHERE run_id = :id AND entry_key = 'input'")
                .param("c", ("v1:t1:" + Base64.getEncoder().encodeToString(sealed)).getBytes(StandardCharsets.UTF_8))
                .param("id", id).update();
        stub.release("api1");

        await().atMost(Duration.ofSeconds(10)).until(() -> json.readTree(http.get("/api/v1/runs/" + runId).body())
                .path("status").asText().equals("FAILED"));
        JsonNode resource = json.readTree(http.get("/api/v1/runs/" + runId).body());
        assertThat(resource.path("errors").findValuesAsText("code")).contains("CONTEXT_DECRYPTION_FAILED");
        assertThat(resource.path("steps").findValuesAsText("status")).containsExactly("SUCCEEDED", "CANCELLED");
        assertThat(stub.invokedStepIds()).doesNotContain("api2");
    }
}
