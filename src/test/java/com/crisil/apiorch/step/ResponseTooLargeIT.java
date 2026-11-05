package com.crisil.apiorch.step;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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

/** US-4.3 AC7: a body above {@code apiorch.run.max-response-bytes} fails the step, while its true size is kept. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "apiorch.run.max-response-bytes=2048")
@ActiveProfiles("test")
class ResponseTooLargeIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void oversizedResponseIsResponseTooLarge() throws Exception {
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:" + api.getPort());
        api.stubFor(post("/big").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"pad\":\"" + "z".repeat(10_000) + "\"}")));
        String code = PublishedFixtures.uniqueCode("BIG");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/big").put("body", "{}");
        PublishedFixtures.publish(jdbc, doc, 1);
        JsonNode run = json.readTree(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").body());
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.at("/errors/0/code").asText()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(jdbc.sql("SELECT response_bytes FROM step_attempt WHERE run_id = :id")
                .param("id", UUID.fromString(run.path("runId").asText())).query(Long.class).single())
                .isEqualTo(10_010L);
    }
}
