package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.security.Roles;
import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;

/**
 * US-4.1 AC8: with 10 Tomcat threads, 200 concurrent sync callers whose step takes 2 s all get 200 within 6 s.
 * Holding a request thread while waiting would need at least 40 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.threads.max=10", "apiorch.executor.step-io.size=220", "spring.datasource.hikari.maximum-pool-size=20"})
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class SyncRunThreadUsageIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;

    @Test
    void twoHundredWaitersShareTenThreads() throws Exception {
        ObjectNode doc = TestPipelines.sequential("THREADS", "only");
        String code = PublishedFixtures.uniqueCode("THREADS");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        stub.reset();
        stub.on("only", inv -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StepOutcome.succeeded(200, null, null, 1, 2000);
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(64);
        HttpClient client = HttpClient.newBuilder().executor(pool).build();
        String token = TestJwt.token("crm", Roles.RUN);
        long start = System.nanoTime();
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            calls.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/pipelines/"
                    + code + "/runs?mode=sync")).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()));
        }
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            assertThat(call.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
        pool.shutdownNow();
    }
}
