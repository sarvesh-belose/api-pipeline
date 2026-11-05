package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import testsupport.web.Http;
import testsupport.web.TestEndpoints;

/** US-1.1 AC4 and US-1.3 AC6: every error is problem+json with the slug type and the correlation ID. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestEndpoints.class)
class ProblemDetailsIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    private JsonNode problem(HttpResponse<String> res, int status, String slug) throws Exception {
        assertThat(res.statusCode()).isEqualTo(status);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("type").asText()).isEqualTo("urn:apiorch:problem:" + slug);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.hasNonNull("title")).isTrue();
        assertThat(body.hasNonNull("instance")).isTrue();
        assertThat(body.path("correlationId").asText()).isNotBlank();
        return body;
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        JsonNode body = problem(http.get("/api/v1/does-not-exist"), 404, "not-found");
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/does-not-exist");
    }

    @Test
    void malformedJsonIsValidationFailed() throws Exception {
        problem(http.postJson("/api/v1/admin/test/typed", "{not json"), 400, "validation-failed");
    }

    @Test
    void beanValidationFailureNamesTheFields() throws Exception {
        JsonNode body = problem(http.postJson("/api/v1/admin/test/typed", "{\"name\":\"\",\"code\":\"toolong\"}"),
                400, "validation-failed");
        assertThat(body.path("errors").findValuesAsText("field")).containsExactlyInAnyOrder("name", "code");
    }

    @Test
    void unhandledExceptionIs500WithoutInternals() throws Exception {
        HttpResponse<String> res = http.get("/api/v1/test/boom");
        problem(res, 500, "internal-error");
        assertThat(res.body()).doesNotContain("IllegalStateException", "secret internal detail", "com.crisil",
                "\tat ");
    }

    @Test
    void wrongMethodIsMethodNotAllowed() throws Exception {
        problem(http.get("/api/v1/admin/test/typed"), 405, "method-not-allowed");
    }

    @Test
    void problemCarriesTheInboundCorrelationId() throws Exception {
        HttpResponse<String> res = http.get("/api/v1/does-not-exist", "X-Correlation-Id", "abc-123");
        assertThat(problem(res, 404, "not-found").path("correlationId").asText()).isEqualTo("abc-123");
        assertThat(res.headers().firstValue("X-Correlation-Id")).hasValue("abc-123");
    }
}
