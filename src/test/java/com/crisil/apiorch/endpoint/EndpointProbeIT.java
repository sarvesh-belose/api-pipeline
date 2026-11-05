package com.crisil.apiorch.endpoint;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-2.1 AC6 (and AC2's healthPath rule): the probe reports reachability with the endpoint's own timeouts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EndpointProbeIT {

    @RegisterExtension
    static WireMockExtension downstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    private String register(String healthPath, int readTimeoutMs) throws Exception {
        String name = "probe-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", EndpointJson.withHealth(name,
                "http://localhost:" + downstream.getPort() + "/svc", healthPath, readTimeoutMs));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return name;
    }

    private JsonNode probe(String name) throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints/" + name + "/probe", null);
        assertThat(res.statusCode()).isEqualTo(200);
        return json.readTree(res.body());
    }

    @Test
    void healthyEndpointIsReachable() throws Exception {
        downstream.stubFor(get("/svc/actuator/health").willReturn(aResponse().withStatus(200)));
        JsonNode result = probe(register("/actuator/health", 2000));
        assertThat(result.path("reachable").asBoolean()).isTrue();
        assertThat(result.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(result.has("durationMs")).isTrue();
        downstream.verify(getRequestedFor(urlEqualTo("/svc/actuator/health")));
    }

    @Test
    void errorStatusIsStillReachable() throws Exception {
        downstream.stubFor(get("/svc/down").willReturn(aResponse().withStatus(503)));
        JsonNode result = probe(register("/down", 2000));
        assertThat(result.path("reachable").asBoolean()).isTrue();
        assertThat(result.path("httpStatus").asInt()).isEqualTo(503);
    }

    @Test
    void responseSlowerThanTheReadTimeoutIsUnreachable() throws Exception {
        downstream.stubFor(get("/svc/slow").willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
        JsonNode result = probe(register("/slow", 300));
        assertThat(result.path("reachable").asBoolean()).isFalse();
        assertThat(result.path("errorClass").asText()).isNotBlank();
        assertThat(result.has("httpStatus")).isFalse();
    }

    @Test
    void healthPathInjectionIsRejectedAtSave() throws Exception {
        for (String bad : new String[] {"health", "/@evil.com", "//evil.com/x", "/../admin", "/x?y=1", "/x#y"}) {
            HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", EndpointJson.withHealth(
                    "bad-" + UUID.randomUUID().toString().substring(0, 8), "https://a.example.com", bad, 1000));
            assertThat(res.statusCode()).as(bad).isEqualTo(400);
            assertThat(res.body()).as(bad).contains("healthPath");
        }
    }

    @Test
    void endpointWithoutHealthPathCannotBeProbed() throws Exception {
        String name = "nohealth-" + UUID.randomUUID().toString().substring(0, 8);
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name, "https://a.example.com"));
        assertThat(http.postJson("/api/v1/admin/endpoints/" + name + "/probe", null).statusCode()).isEqualTo(400);
        assertThat(http.postJson("/api/v1/admin/endpoints/missing-one/probe", null).statusCode()).isEqualTo(404);
    }
}
