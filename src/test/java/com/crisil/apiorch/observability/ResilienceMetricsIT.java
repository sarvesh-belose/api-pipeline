package com.crisil.apiorch.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.persistence.RunRows;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-7.5 AC3 and AC4: circuit-breaker meters per endpoint that follow a runtime rebuild without stale series, the
 * recovery counters, runs driven by this node and unfinished runs by status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apiorch.recovery.sweep-interval-ms=300", "apiorch.lease.renew-interval-ms=300",
        "apiorch.metrics.run-status-interval-ms=300"})
@ActiveProfiles("test")
@AutoConfigureObservability
class ResilienceMetricsIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    @Autowired JdbcClient jdbc;
    @Autowired RunRows rows;

    private String scrape() throws Exception {
        return Http.anonymous(managementPort).get("/actuator/prometheus").body();
    }

    private static double value(String scrape, String series) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(series) + " ([0-9.Ee+-]+)$").matcher(scrape);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    private static int count(String scrape, String regex) {
        Matcher m = Pattern.compile(regex).matcher(scrape);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void circuitMetersFollowTheRuntimeAcrossARebuild() throws Exception {
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503)));
        Http http = new Http(port);
        String name = "cbm-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"name":"%s","baseUrl":"http://localhost:%d","connectTimeoutMs":2000,"readTimeoutMs":5000,
                 "resilience":{"circuitBreaker":{"slidingWindowSize":5,"minimumCalls":5,"failureRateThreshold":50,
                 "waitInOpenMs":60000,"halfOpenCalls":1}}}""".formatted(name, api.getPort());
        assertThat(http.postJson("/api/v1/admin/endpoints", body).statusCode()).isEqualTo(201);
        String code = PublishedFixtures.uniqueCode("CBM");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", name, "GET", "/x")
                .set("retry", DslJsonMapper.get().createObjectNode().put("maxAttempts", 1));
        PublishedFixtures.publish(jdbc, doc, 1);
        for (int i = 0; i < 5; i++) {
            http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}");
        }

        String open = scrape();
        String stateOpen = "resilience4j_circuitbreaker_state{name=\"" + name + "\",state=\"open\"}";
        assertThat(value(open, stateOpen)).isEqualTo(1.0);
        assertThat(open).contains("resilience4j_circuitbreaker_failure_rate{name=\"" + name + "\"");
        assertThat(open).containsPattern("resilience4j_circuitbreaker_calls_seconds_count\\{[^}]*name=\"" + name);

        String etag = http.get("/api/v1/admin/endpoints/" + name).headers().firstValue("ETag").orElseThrow();
        assertThat(http.putJson("/api/v1/admin/endpoints/" + name, body.replace("\"halfOpenCalls\":1",
                "\"halfOpenCalls\":2"), etag).statusCode()).isEqualTo(200);
        String rebuilt = scrape();
        assertThat(value(rebuilt, stateOpen)).isEqualTo(0.0);
        assertThat(value(rebuilt, "resilience4j_circuitbreaker_state{name=\"" + name + "\",state=\"closed\"}"))
                .isEqualTo(1.0);
        assertThat(count(rebuilt, "(?m)^resilience4j_circuitbreaker_state\\{name=\"" + Pattern.quote(name)
                + "\",state=\"closed\"\\}")).as("one series, not a stale duplicate").isEqualTo(1);
    }

    @Test
    void recoveryAndRunGaugesAreExposed() throws Exception {
        api.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        String code = PublishedFixtures.uniqueCode("RECM");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "only", endpoint, "POST", "/only").put("body", "{}");
        PublishedFixtures.publish(jdbc, doc, 1);
        UUID id = UUID.randomUUID();
        rows.insertRun(id, code, 1, "ASYNC", "c-metrics", "system-a", "dead-node", -1000);
        rows.insertStep(id, "only", "SYNC");
        rows.putContext(id, "input", DslJsonMapper.get().createObjectNode());
        rows.markRunning(id, 60000);
        rows.markStepRunning(id, "only");

        await().atMost(Duration.ofSeconds(20)).until(() -> rows.run(id).orElseThrow().status().terminal());
        await().atMost(Duration.ofSeconds(5)).until(() -> value(scrape(), "apiorch_recovery_runs_recovered_total") >= 1);
        String scrape = scrape();
        assertThat(value(scrape, "apiorch_recovery_steps_unknown_total")).isGreaterThanOrEqualTo(1.0);
        assertThat(value(scrape, "apiorch_runs_driven")).isGreaterThanOrEqualTo(0.0);
        // registered at its first refresh, every apiorch.metrics.run-status-interval-ms
        await().atMost(Duration.ofSeconds(5))
                .until(() -> value(scrape(), "apiorch_runs_by_status{status=\"RUNNING\"}") >= 0.0);
        assertThat(scrape).doesNotContain("runId=").doesNotContain(id.toString());
    }
}
