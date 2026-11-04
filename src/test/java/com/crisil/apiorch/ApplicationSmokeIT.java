package com.crisil.apiorch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Walking skeleton (Sprint 0, S5): the app boots, probes answer on the management port, errors are problem+json. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationSmokeIT {

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString());
    }

    @Test
    void readinessAndLivenessAreUpOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        HttpResponse<String> readiness = get("http://localhost:" + managementPort + "/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        assertThat(get("http://localhost:" + managementPort + "/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    @Test
    void unknownRouteIsProblemJson() throws Exception {
        HttpResponse<String> res = get("http://localhost:" + port + "/api/v1/does-not-exist");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
    }
}
