package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import testsupport.web.Http;
import testsupport.web.TestEndpoints;

/** US-1.3 AC1 and AC6 end to end, including a problem produced during an MVC async dispatch. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestEndpoints.class)
class CorrelationIdIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired OutboundHeaders outbound;

    @Test
    void asyncDispatchProblemKeepsTheCorrelationId() throws Exception {
        HttpResponse<String> res = new Http(port).get("/api/v1/test/async-fail", "X-Correlation-Id", "abc-123");
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.headers().firstValue("X-Correlation-Id")).hasValue("abc-123");
        assertThat(json.readTree(res.body()).path("correlationId").asText()).isEqualTo("abc-123");
        assertThat(json.readTree(res.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:illegal-run-state");
    }

    @Test
    void generatedIdIsEchoedAndCarriedInTheProblem() throws Exception {
        HttpResponse<String> res = new Http(port).get("/api/v1/nope");
        String header = res.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(json.readTree(res.body()).path("correlationId").asText()).isEqualTo(header);
    }

    @Test
    void outboundHeadersCarryTheCorrelationId() {
        assertThat(outbound.correlation("abc-123")).containsEntry("X-Correlation-Id", "abc-123");
    }
}
