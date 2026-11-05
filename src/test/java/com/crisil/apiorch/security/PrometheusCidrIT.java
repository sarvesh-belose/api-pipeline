package com.crisil.apiorch.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import testsupport.web.Http;

/** US-1.2 AC5: from an address outside the allowed networks, Prometheus is refused. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "apiorch.security.metrics-allowed-cidrs=10.0.0.0/8")
@ActiveProfiles("test")
@AutoConfigureObservability
class PrometheusCidrIT {

    @Value("${local.management.port}") int managementPort;

    @Test
    void prometheusIsRefusedFromOtherAddresses() throws Exception {
        Http management = Http.anonymous(managementPort);
        assertThat(management.get("/actuator/prometheus").statusCode()).isIn(401, 403);
        assertThat(management.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
    }
}
