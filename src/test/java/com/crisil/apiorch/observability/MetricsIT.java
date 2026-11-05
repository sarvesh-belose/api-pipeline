package com.crisil.apiorch.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.engine.TestPipelines;
import testsupport.web.Drafts;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/**
 * US-7.5 core (AC1, AC2, AC4 publish counter, AC5): a successful and a failing run are scraped from
 * {@code /actuator/prometheus}; run and step timers carry histogram buckets, attempts and payload sizes are tagged by
 * endpoint, and no registered meter has a per-run tag.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureObservability
class MetricsIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry registry;

    @Test
    void runStepAttemptAndPublishMetricsAreExposedWithoutPerRunTags() throws Exception {
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        api.stubFor(post("/ok").willReturn(aResponse().withStatus(200).withBody("{}")));
        api.stubFor(post("/down").willReturn(aResponse().withStatus(503)));
        String code = PublishedFixtures.uniqueCode("MET");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/ok").put("body", "{ 'a': 1 }");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/down");
        PublishedFixtures.publish(jdbc, doc, 1);
        assertThat(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").statusCode()).isEqualTo(200);

        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
        String published = PublishedFixtures.uniqueCode("MET");
        ObjectNode definition = TestPipelines.onboardV1();
        definition.put("code", published);
        Drafts.savePublished(http, definition);

        String scrape = Http.anonymous(managementPort).get("/actuator/prometheus").body();
        assertThat(scrape).contains("apiorch_run_duration_seconds_bucket{")
                .contains("pipeline=\"" + code + "\"").contains("status=\"FAILED\"");
        assertThat(scrape).contains("apiorch_step_duration_seconds_bucket{").contains("outcome=\"HTTP_ERROR\"")
                .contains("outcome=\"SUCCEEDED\"");
        assertThat(scrape).containsPattern("apiorch_step_attempts_total\\{[^}]*endpoint=\"" + endpoint
                + "\"[^}]*outcome=\"SUCCESS\"");
        assertThat(scrape).contains("apiorch_payload_request_bytes_count{").contains("apiorch_payload_response_bytes_sum{");
        assertThat(scrape).containsPattern("apiorch_definition_published_total\\{[^}]*pipeline=\"" + published + "\"");

        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> assertThat(ApiorchMetrics.FORBIDDEN_TAG_KEYS)
                    .as(meter.getId().getName()).doesNotContain(tag.getKey().toLowerCase(Locale.ROOT)));
        }
    }
}
