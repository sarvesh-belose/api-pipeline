package com.crisil.apiorch.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.security.Roles;

import testsupport.cluster.ClusterTestSupport;
import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-5.5 AC5 and AC7 on two nodes that both renew and sweep: a run longer than its lease stays with its node, and
 * a node shutting down refuses new runs with {@code 503}, lets
 * its in-flight calls finish and be recorded, then releases its runs with a wake-up so the other node continues them
 * without calling anything twice. The shutdown test runs last because it stops node A.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GracefulShutdownIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String[] FAST = {"--apiorch.lease.duration-ms=2000", "--apiorch.lease.renew-interval-ms=300",
            "--apiorch.recovery.sweep-interval-ms=300"};

    static ClusterTestSupport cluster;
    static ClusterTestSupport.Node a;
    static ClusterTestSupport.Node b;
    static JdbcClient jdbc;
    static String endpoint;

    @BeforeAll
    static void startTwoNodes() throws Exception {
        cluster = ClusterTestSupport.onH2("shutdown_" + System.nanoTime());
        a = cluster.start("node-a", FAST);
        b = cluster.start("node-b", FAST);
        jdbc = b.bean(JdbcClient.class);
        endpoint = HttpPipelines.endpoint(new Http(a.port()), "http://localhost:" + api.getPort());
    }

    @AfterAll
    static void stopNodes() {
        cluster.close();
    }

    private static String pipeline(String... steps) {
        String code = PublishedFixtures.uniqueCode("SHUT");
        ObjectNode doc = HttpPipelines.pipeline(code);
        for (String id : steps) {
            HttpPipelines.step(doc, id, endpoint, "GET", "/" + id)
                    .set("retry", DslJsonMapper.get().createObjectNode().put("maxAttempts", 1));
        }
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private static void respond(String path, int delayMs) {
        api.stubFor(any(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withFixedDelay(delayMs)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    private static String status(UUID id) {
        return jdbc.sql("SELECT status FROM pipeline_run WHERE id = :id").param("id", id).query(String.class).single();
    }

    private static long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private static List<String> paths(UUID runId) {
        return api.getAllServeEvents().stream()
                .filter(e -> runId.toString().equals(e.getRequest().getHeader("X-Apiorch-Run-Id")))
                .map(e -> e.getRequest().getUrl()).toList();
    }

    private static UUID start(Http http, String code) throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        return UUID.fromString(DslJsonMapper.get().readTree(res.body()).path("runId").asText());
    }

    @Test
    @Order(1)
    void aRunLongerThanItsLeaseStaysWithItsNode() throws Exception {
        api.resetAll();
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        respond("/slow", 4000);
        String code = pipeline("slow", "after");
        UUID id = start(new Http(a.port()), code);
        await().atMost(Duration.ofSeconds(20)).until(() -> status(id).equals("COMPLETED"));
        assertThat(count("SELECT lease_epoch FROM pipeline_run WHERE id = :id", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM run_event WHERE run_id = :id AND event_type = 'RUN_RECOVERED'", id))
                .isZero();
        assertThat(paths(id)).containsExactlyInAnyOrder("/slow", "/after");
    }

    @Test
    @Order(3)
    void aStoppingNodeFinishesItsCallsAndHandsItsRunsOver() throws Exception {
        api.resetAll();
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        respond("/first", 1500);
        String code = pipeline("first", "second");
        Http onA = new Http(a.port()).as(TestJwt.token("system-a", Roles.RUN));
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            runs.add(start(onA, code));
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> api.getAllServeEvents().size() >= 3);

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> cluster.stop(a));
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            try {
                HttpResponse<String> refused = onA.postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
                return refused.statusCode() == 503 && refused.body().contains("shutting-down");
            } catch (java.io.IOException e) {
                return false;
            }
        });
        stopping.get();

        await().atMost(Duration.ofSeconds(20)).until(() -> runs.stream().allMatch(id -> status(id).equals("COMPLETED")));
        for (UUID id : runs) {
            assertThat(paths(id)).as("%s", id).containsExactlyInAnyOrder("/first", "/second");
            String winner = jdbc.sql("SELECT instance_id FROM run_event WHERE run_id = :id AND event_type = "
                    + "'RUN_RECOVERED'").param("id", id).query(String.class).single();
            assertThat(winner).isEqualTo("node-b");
            assertThat(count("SELECT COUNT(*) FROM step_attempt WHERE run_id = :id AND step_id = 'first'", id))
                    .isEqualTo(1);
        }
        List<ServeEvent> seconds = api.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().equals("/second")).toList();
        assertThat(seconds).hasSize(3);
    }
}
