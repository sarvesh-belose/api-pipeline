package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.security.Roles;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-4.7 AC5: 20 identical requests with one key at once create exactly one run and one call per step; the others
 * get that run back. One context here; the two-context version waits for {@code ClusterTestSupport} (US-5.5).
 */
@Import(StubExecutorConfig.class)
abstract class IdempotencyRaceContract {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    @Test
    void concurrentRepeatsCreateOneRun() throws Exception {
        ObjectNode doc = TestPipelines.sequential("IDRACE", "api1", "api2");
        String code = PublishedFixtures.uniqueCode("IDRACE");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        Http http = new Http(port).as(TestJwt.token("system-a", Roles.RUN));

        int n = 20;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return http.send("POST", "/api/v1/pipelines/" + code + "/runs?mode=sync", "{\"name\":\"r\"}", null,
                            "Idempotency-Key", "RACE-1");
                }));
            }
            go.countDown();
            Set<String> runIds = new HashSet<>();
            int replays = 0;
            for (Future<HttpResponse<String>> f : futures) {
                HttpResponse<String> res = f.get();
                assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
                runIds.add(json.readTree(res.body()).path("runId").asText());
                replays += res.headers().firstValue("Idempotent-Replayed").isPresent() ? 1 : 0;
            }
            assertThat(runIds).hasSize(1);
            assertThat(replays).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM pipeline_run WHERE pipeline_code = :c").param("c", code)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2");
    }
}
