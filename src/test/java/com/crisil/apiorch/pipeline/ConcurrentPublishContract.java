package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.observability.ApiProblemException;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.TestPipelines;
import testsupport.web.Drafts;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/**
 * US-3.3 AC5: two publishes of one draft at once give exactly one version; the loser gets {@code version-conflict},
 * never {@code 404}. The winner holds its transaction open after publishing, so the loser is waiting on the
 * {@code pipeline} row lock when the winner commits.
 */
abstract class ConcurrentPublishContract {

    @Value("${local.server.port}") int port;
    @Autowired PublishService publishing;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;

    @Test
    void exactlyOnePublishWins() throws Exception {
        Http http = new Http(port);
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
        String code = PublishedFixtures.uniqueCode("RACE");
        ObjectNode doc = TestPipelines.onboardV1();
        doc.put("code", code);
        Drafts.save(http, doc);

        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch firstPublished = new CountDownLatch(1);
        List<Object> results = new ArrayList<>();
        CompletableFuture<Object> winner = CompletableFuture.supplyAsync(() -> attempt(() -> tx.execute(s -> {
            await(start);
            Object published = publishing.publish(code, "first", "admin-a");
            firstPublished.countDown();
            sleep(600);
            return published;
        })));
        CompletableFuture<Object> loser = CompletableFuture.supplyAsync(() -> attempt(() -> {
            await(start);
            return publishing.publish(code, "second", "admin-b");
        }));
        results.add(winner.get(20, TimeUnit.SECONDS));
        results.add(loser.get(20, TimeUnit.SECONDS));

        long created = results.stream().filter(r -> !(r instanceof ApiProblemException)).count();
        assertThat(created).as(results.toString()).isEqualTo(1);
        ApiProblemException conflict = (ApiProblemException) results.stream()
                .filter(r -> r instanceof ApiProblemException).findFirst().orElseThrow();
        assertThat(conflict.slug()).isEqualTo("version-conflict");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM pipeline_definition WHERE code = :c AND version > 0")
                .param("c", code).query(Integer.class).single()).isEqualTo(1);
    }

    private static Object attempt(java.util.function.Supplier<Object> call) {
        try {
            return call.get();
        } catch (ApiProblemException e) {
            return e;
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
