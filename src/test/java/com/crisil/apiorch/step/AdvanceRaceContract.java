package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-4.4 AC6: a diamond (a → b, c → d) run 100 times, where b and c complete together and both call
 * {@code advance}. The run-row lock serializes them, so every step is requested exactly once per run.
 */
abstract class AdvanceRaceContract {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void everyStepRunsExactlyOncePerRun() throws Exception {
        api.resetAll();
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{}")));
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        String code = PublishedFixtures.uniqueCode("RACE");
        ObjectNode doc = HttpPipelines.pipeline(code);
        doc.put("syncWaitMs", 30000);
        HttpPipelines.step(doc, "a", endpoint, "POST", "/a").put("body", "{}");
        HttpPipelines.step(doc, "b", endpoint, "POST", "/b").put("body", "{}").putArray("dependsOn").add("a");
        HttpPipelines.step(doc, "c", endpoint, "POST", "/c").put("body", "{}").putArray("dependsOn").add("a");
        HttpPipelines.step(doc, "d", endpoint, "POST", "/d").put("body", "{}").putArray("dependsOn").add("b").add("c");
        PublishedFixtures.publish(jdbc, doc, 1);

        int runs = 100;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < runs; i++) {
                futures.add(pool.submit(() -> http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}")));
            }
            for (Future<HttpResponse<String>> f : futures) {
                HttpResponse<String> res = f.get();
                assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
                assertThat(json.readTree(res.body()).path("status").asText()).isEqualTo("COMPLETED");
            }
        } finally {
            pool.shutdownNow();
        }
        Map<String, List<String>> byRun = new HashMap<>();
        for (ServeEvent e : api.getAllServeEvents()) {
            String run = e.getRequest().getHeader("X-Apiorch-Run-Id");
            if (run != null) {
                byRun.computeIfAbsent(run, k -> new ArrayList<>()).add(e.getRequest().getUrl());
            }
        }
        assertThat(byRun).hasSize(runs);
        byRun.forEach((run, paths) -> assertThat(paths).as(run).containsExactlyInAnyOrder("/a", "/b", "/c", "/d"));
    }
}
