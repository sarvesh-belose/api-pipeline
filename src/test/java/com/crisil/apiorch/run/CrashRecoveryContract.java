package com.crisil.apiorch.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.security.Roles;

import testsupport.cluster.ClusterTestSupport;
import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-5.5 AC1–AC4 with two nodes on one database. Node A's renewal is frozen (its lease is 2 s and it never renews)
 * while its call to api2 hangs for 3 s, as in a long GC pause. Node B claims the run once the lease expires, records
 * {@code RUN_RECOVERED}, sends api2 again with the same {@code Idempotency-Key} (a GET) and finishes the run; api1 is
 * not called again. When A's call returns, A finds no row for {@code (A, epoch 1)} and writes nothing. Also AC4: two
 * sweepers recover every orphan exactly once. SQL Server is the evidence for the lock and claim behaviour, because
 * H2 ignores the lock hints (ADR-003 F3, rule 6).
 */
abstract class CrashRecoveryContract {

    static final String[] FAST = {"--apiorch.lease.duration-ms=2000", "--apiorch.lease.renew-interval-ms=300",
            "--apiorch.recovery.sweep-interval-ms=300"};

    /** Two or more nodes on one database: named H2, or the shared SQL Server container (slow tier). */
    abstract ClusterTestSupport newCluster();

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().containerThreads(64)).build();

    private final ClusterTestSupport cluster = newCluster();

    @AfterEach
    void tearDown() {
        cluster.close();
    }

    @Test
    void anotherNodeContinuesTheRunAndTheFencedNodeWritesNothing() throws Exception {
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{}")));
        api.stubFor(any(urlPathEqualTo("/api2")).willReturn(aResponse().withStatus(200).withFixedDelay(3000)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        ClusterTestSupport.Node a = cluster.start("node-a", "--apiorch.lease.duration-ms=2000",
                "--apiorch.lease.renew-interval-ms=600000", "--apiorch.recovery.sweep-interval-ms=600000");
        ClusterTestSupport.Node b = cluster.start("node-b", "--apiorch.lease.duration-ms=10000",
                "--apiorch.lease.renew-interval-ms=1000", "--apiorch.recovery.sweep-interval-ms=300");
        JdbcClient jdbc = b.bean(JdbcClient.class);
        Http onA = new Http(a.port());
        String endpoint = HttpPipelines.endpoint(onA, "http://localhost:" + api.getPort());
        String code = PublishedFixtures.uniqueCode("CRASH");
        ObjectNode doc = HttpPipelines.pipeline(code);
        for (String id : List.of("api1", "api2", "api3")) {
            HttpPipelines.step(doc, id, endpoint, "GET", "/" + id)
                    .set("retry", DslJsonMapper.get().createObjectNode().put("maxAttempts", 1));
        }
        PublishedFixtures.publish(jdbc, doc, 1);

        JsonNode started = DslJsonMapper.get().readTree(onA.as(TestJwt.token("system-a", Roles.RUN))
                .postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}").body());
        UUID runId = UUID.fromString(started.path("runId").asText());

        await().atMost(Duration.ofSeconds(30)).until(() -> "COMPLETED".equals(jdbc.sql(
                "SELECT status FROM pipeline_run WHERE id = :id").param("id", runId).query(String.class).single()));
        Map<String, Object> run = jdbc.sql("SELECT owner_instance, lease_epoch FROM pipeline_run WHERE id = :id")
                .param("id", runId).query().singleRow();
        assertThat(((Number) run.get("lease_epoch")).longValue()).isEqualTo(2);
        JsonNode recovered = DslJsonMapper.get().readTree(jdbc.sql("SELECT details FROM run_event WHERE run_id = :id "
                + "AND event_type = 'RUN_RECOVERED'").param("id", runId).query(String.class).single());
        assertThat(recovered.path("previousOwner").asText()).isEqualTo("node-a");
        assertThat(recovered.path("newOwner").asText()).isEqualTo("node-b");

        // A's hanging call returns after 3 s; give it time to try (and fail) to record
        Thread.sleep(4000);
        List<ServeEvent> calls = api.getAllServeEvents();
        assertThat(calls.stream().filter(e -> e.getRequest().getUrl().equals("/api1"))).hasSize(1);
        List<ServeEvent> api2 = calls.stream().filter(e -> e.getRequest().getUrl().equals("/api2")).toList();
        assertThat(api2).hasSize(2);
        assertThat(api2.stream().map(e -> e.getRequest().getHeader("Idempotency-Key")).distinct().toList())
                .containsExactly(runId + ":api2:1");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM step_attempt WHERE run_id = :id AND step_id = 'api2'")
                .param("id", runId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND instance_id = 'node-a' AND seq > ("
                + "SELECT seq FROM run_event WHERE run_id = :id AND event_type = 'RUN_RECOVERED')")
                .param("id", runId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = 'RUN_COMPLETED'")
                .param("id", runId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void twoSweepersRecoverEachOrphanOnce() throws Exception {
        api.resetAll();
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        ClusterTestSupport.Node a = cluster.start("node-a", FAST);
        ClusterTestSupport.Node b = cluster.start("node-b", FAST);
        JdbcClient jdbc = b.bean(JdbcClient.class);
        // a generous read timeout: 20 calls at once on a loaded machine with three contexts can take seconds
        String endpoint = "sweep-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(new Http(a.port()).postJson("/api/v1/admin/endpoints", """
                {"name":"%s","baseUrl":"http://localhost:%d","connectTimeoutMs":5000,"readTimeoutMs":30000}"""
                .formatted(endpoint, api.getPort())).statusCode()).isEqualTo(201);
        String code = PublishedFixtures.uniqueCode("SWEEP");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "only", endpoint, "GET", "/only")
                .set("retry", DslJsonMapper.get().createObjectNode().put("maxAttempts", 1));
        PublishedFixtures.publish(jdbc, doc, 1);
        RunRows rows = b.bean(RunRows.class);
        List<UUID> orphans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            rows.insertRun(id, code, 1, "ASYNC", "c-orphan", "system-a", "dead-node", -1000);
            rows.insertStep(id, "only", "SYNC");
            rows.putContext(id, "input", DslJsonMapper.get().createObjectNode());
            orphans.add(id);
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> orphans.stream().allMatch(id -> "COMPLETED".equals(jdbc.sql(
                "SELECT status FROM pipeline_run WHERE id = :id").param("id", id).query(String.class).single())));
        for (UUID id : orphans) {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = 'RUN_RECOVERED'")
                    .param("id", id).query(Long.class).single()).as("%s", id).isEqualTo(1);
            assertThat(api.getAllServeEvents().stream()
                    .filter(e -> id.toString().equals(e.getRequest().getHeader("X-Apiorch-Run-Id")))).as("%s", id)
                    .hasSize(1);
        }
        List<String> winners = orphans.stream().map(id -> jdbc.sql("SELECT instance_id FROM run_event WHERE run_id = :id "
                + "AND event_type = 'RUN_RECOVERED'").param("id", id).query(String.class).single()).toList();
        assertThat(winners).allMatch(w -> w.equals("node-a") || w.equals("node-b"));
    }
}
