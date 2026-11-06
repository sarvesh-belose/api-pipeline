package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.web.Http;

/**
 * US-5.1 AC1 and AC2: the step's {@code timeoutMs} bounds its attempt ({@code TIMEOUT}, through the bucketed client
 * that keeps the exchange recorder), and a refused connection is {@code CONNECT_ERROR}, never {@code TIMEOUT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StepTimeoutIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    private JsonNode run(String endpoint, Integer timeoutMs) throws Exception {
        String code = PublishedFixtures.uniqueCode("TMO");
        ObjectNode doc = HttpPipelines.pipeline(code);
        ObjectNode step = HttpPipelines.step(doc, "api2", endpoint, "POST", "/slow").put("body", "{}");
        if (timeoutMs != null) {
            step.put("timeoutMs", timeoutMs);
        }
        PublishedFixtures.publish(jdbc, doc, 1);
        return json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").body());
    }

    @Test
    void theStepTimeoutEndsTheAttempt() throws Exception {
        api.stubFor(post("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(5000)));
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        JsonNode run = run(endpoint, 2000);

        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("TIMEOUT");
        Map<String, Object> attempt = jdbc.sql("SELECT outcome, duration_ms FROM step_attempt WHERE run_id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query().singleRow();
        assertThat(attempt.get("outcome")).isEqualTo("TIMEOUT");
        assertThat(((Number) attempt.get("duration_ms")).longValue()).isBetween(2000L, 3999L);
    }

    @Test
    void aRefusedConnectionIsAConnectError() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + closed);
        JsonNode run = run(endpoint, null);

        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("CONNECT_ERROR");
    }
}
