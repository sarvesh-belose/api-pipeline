package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

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

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Drafts;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/**
 * US-3.3 AC3, the headline reorder: v1 runs api1→2→3→4→5; once v2 (api1, api2, api5, api4, api3) is published, the
 * next run on this node is pinned to v2 and runs in v2 order, with no restart. A version written by another node
 * (no local event) is picked up within {@code latest-cache-ttl-ms}. Step order is read from the stub executor until
 * US-4.3 adds real HTTP steps; then the WireMock request order is asserted as well.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class ReorderNoRedeployIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() throws Exception {
        http = new Http(port);
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
        stub.reset();
    }

    private JsonNode run(String code) throws Exception {
        stub.reset();
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{\"name\":\"Ada\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    @Test
    void reorderTakesEffectForTheNextRunWithoutRestart() throws Exception {
        String code = PublishedFixtures.uniqueCode("REORDER");
        ObjectNode v1 = TestPipelines.onboardV1();
        v1.put("code", code);
        assertThat(Drafts.savePublished(http, v1)).isEqualTo(1);
        JsonNode first = run(code);
        assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2", "api3", "api4", "api5");

        ObjectNode v2 = TestPipelines.onboardReordered();
        v2.put("code", code);
        assertThat(Drafts.savePublished(http, v2)).isEqualTo(2);
        JsonNode second = run(code);
        assertThat(second.path("version").asInt()).isEqualTo(2);
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2", "api5", "api4", "api3");
    }

    @Test
    void versionPublishedElsewhereIsSeenWithinTheCacheTtl() throws Exception {
        String code = PublishedFixtures.uniqueCode("REORDER");
        ObjectNode v1 = TestPipelines.onboardV1();
        v1.put("code", code);
        Drafts.savePublished(http, v1);
        assertThat(run(code).path("version").asInt()).isEqualTo(1);

        ObjectNode v2 = TestPipelines.onboardReordered();
        v2.put("code", code);
        PublishedFixtures.publish(jdbc, v2, 2);
        long published = System.nanoTime();
        await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(250))
                .until(() -> run(code).path("version").asInt() == 2);
        assertThat(Duration.ofNanos(System.nanoTime() - published)).isLessThan(Duration.ofSeconds(7));
        assertThat(stub.invokedStepIds()).isEqualTo(List.of("api1", "api2", "api5", "api4", "api3"));
    }
}
