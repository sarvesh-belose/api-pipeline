package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.security.Roles;

import testsupport.cluster.ClusterTestSupport;
import testsupport.engine.PublishedFixtures;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-4.2 AC6: a run started and finished on node B is polled on node A, which returns the persisted state. Two
 * contexts share one named H2 database. The step's endpoint is not registered, so the run ends {@code FAILED} with
 * {@code ENDPOINT_NOT_FOUND} on B at once; what matters here is that A reports B's final state.
 */
class CrossNodePollIT {

    private final ClusterTestSupport cluster = ClusterTestSupport.onH2("crossnode_" + System.nanoTime());

    @AfterEach
    void tearDown() {
        cluster.close();
    }

    @Test
    void anyNodeReturnsTheLatestPersistedState() throws Exception {
        ClusterTestSupport.Node a = cluster.start("node-a");
        ClusterTestSupport.Node b = cluster.start("node-b");
        ObjectNode doc = TestPipelines.sequential("XNODE", "api1");
        String code = PublishedFixtures.uniqueCode("XNODE");
        doc.put("code", code);
        PublishedFixtures.publish(b.bean(JdbcClient.class), doc, 1);

        String token = TestJwt.token("system-a", Roles.RUN);
        Http onB = new Http(b.port()).as(token);
        Http onA = new Http(a.port()).as(token);
        HttpResponse<String> started = onB.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String runId = DslJsonMapper.get().readTree(started.body()).path("runId").asText();

        await().atMost(Duration.ofSeconds(10)).until(() -> b.bean(JdbcClient.class)
                .sql("SELECT COUNT(*) FROM pipeline_run WHERE id = :id AND ended_at IS NOT NULL")
                .param("id", java.util.UUID.fromString(runId)).query(Integer.class).single() == 1);
        HttpResponse<String> polled = onA.get("/api/v1/runs/" + runId);
        assertThat(polled.statusCode()).as(polled.body()).isEqualTo(200);
        JsonNode resource = DslJsonMapper.get().readTree(polled.body());
        assertThat(resource.path("status").asText()).isEqualTo("FAILED");
        assertThat(resource.at("/errors/0/code").asText()).isEqualTo("ENDPOINT_NOT_FOUND");
        assertThat(polled.headers().firstValue("Retry-After")).isEmpty();
    }
}
