package com.crisil.apiorch.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import testsupport.web.Http;
import testsupport.web.TestEndpoints;

/**
 * US-1.2 AC4 and AC5: the callback chain ignores bearer headers; probes are open, Prometheus is limited to the
 * allowed networks (localhost here), and every other actuator endpoint is refused (401 or 403, ADR-003 F12).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=health,info,prometheus,env")
@ActiveProfiles("test")
@AutoConfigureObservability
@Import(TestEndpoints.class)
class CallbackAndActuatorSecurityIT {

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;

    @Test
    void callbackWithAnUnrelatedBearerHeaderReachesTheController() throws Exception {
        HttpResponse<String> res = new Http(port).as("xyz").postJson("/api/v1/callbacks/r1/s1?t=abc", "{\"a\":1}");
        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    void probesAreOpenPrometheusIsLocalOtherEndpointsAreRefused() throws Exception {
        Http management = Http.anonymous(managementPort);
        assertThat(management.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(management.get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(management.get("/actuator/prometheus").statusCode()).isEqualTo(200);
        assertThat(management.get("/actuator/env").statusCode()).isIn(401, 403);
        assertThat(management.get("/actuator/info").statusCode()).isIn(401, 403);
    }
}
