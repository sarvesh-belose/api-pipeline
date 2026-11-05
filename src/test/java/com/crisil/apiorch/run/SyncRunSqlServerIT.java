package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.step.StepOutcome;

import testsupport.db.SharedSqlServer;
import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/** Slow tier: a sync run end to end on SQL Server, with the real row lock and DAG steps completing concurrently. */
@Tag("slow")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
@Import(StubExecutorConfig.class)
class SyncRunSqlServerIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void parallelStepsCompleteOnSqlServer() throws Exception {
        ObjectNode doc = TestPipelines.sequential("MSSQL_RUN", "a", "b", "c", "d");
        for (int i = 1; i <= 3; i++) {
            ((ObjectNode) doc.withArray("steps").get(i)).putArray("dependsOn").add("a");
        }
        String code = PublishedFixtures.uniqueCode("MSSQL_RUN");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        stub.reset();
        stub.on("c", StepOutcome.succeeded(201, null, json.createObjectNode().put("ok", true), 1, 5));
        HttpResponse<String> res = new Http(port).postJson("/api/v1/pipelines/" + code + "/runs", "{}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(json.readTree(res.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(stub.invokedStepIds()).hasSize(4).startsWith("a");
        String runId = json.readTree(res.body()).path("runId").asText();
        assertThat(jdbc.sql("SELECT MAX(seq) - COUNT(*) FROM run_event WHERE run_id = :id").param("id", runId)
                .query(Long.class).single()).as("gap-free seq").isZero();
    }
}
