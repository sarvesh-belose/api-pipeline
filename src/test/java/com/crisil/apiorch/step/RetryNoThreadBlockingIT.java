package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-5.2 AC7: with two {@code step-io} threads and 20 runs whose step is in a long backoff, another run still
 * completes at once, because no thread waits out a backoff.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "apiorch.executor.step-io.size=2")
@ActiveProfiles("test")
class RetryNoThreadBlockingIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void stepsInBackoffHoldNoThread() throws Exception {
        api.stubFor(any(urlPathEqualTo("/flaky")).willReturn(aResponse().withStatus(503)));
        api.stubFor(any(urlPathEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());

        String flaky = PublishedFixtures.uniqueCode("BACKOFF");
        ObjectNode doc = HttpPipelines.pipeline(flaky);
        HttpPipelines.step(doc, "api1", endpoint, "GET", "/flaky").set("retry", json.createObjectNode()
                .put("maxAttempts", 2).put("backoff", "FIXED").put("initialMs", 8000).put("maxMs", 10000));
        PublishedFixtures.publish(jdbc, doc, 1);
        String quick = PublishedFixtures.uniqueCode("QUICK");
        ObjectNode q = HttpPipelines.pipeline(quick);
        HttpPipelines.step(q, "api1", endpoint, "GET", "/ok");
        PublishedFixtures.publish(jdbc, q, 1);

        for (int i = 0; i < 20; i++) {
            http.postJson("/api/v1/pipelines/" + flaky + "/runs?mode=async", "{}");
        }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT COUNT(*) FROM run_event e JOIN pipeline_run r ON r.id = e.run_id "
                        + "WHERE r.pipeline_code = :c AND e.event_type = 'RETRY_SCHEDULED'")
                .param("c", flaky).query(Long.class).single() == 20);

        long start = System.nanoTime();
        HttpResponse<String> res = http.postJson("/api/v1/pipelines/" + quick + "/runs?mode=sync", "{}");
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(json.readTree(res.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(tookMs).isLessThan(4000);
    }
}
