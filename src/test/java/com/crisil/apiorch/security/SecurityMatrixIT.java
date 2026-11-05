package com.crisil.apiorch.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-1.2 AC1, AC3 and AC8: every README §9 route against each role. "Allowed" means the request passed security
 * (anything but 401/403: the controller may still answer 404, 400 or 405, because several routes arrive in later
 * stories); "denied" means 403 forbidden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityMatrixIT {

    @Value("${local.server.port}") int port;

    static Stream<Arguments> matrix() {
        // method, path, run, admin, ops  (true = allowed)
        return Stream.of(
                Arguments.of("POST", "/api/v1/pipelines/DEMO/runs", true, false, false),
                Arguments.of("GET", "/api/v1/runs/r1", true, false, true),
                Arguments.of("GET", "/api/v1/runs/r1/timeline", true, false, true),
                Arguments.of("GET", "/api/v1/runs", true, false, true),
                Arguments.of("GET", "/api/v1/runs/r1/steps/api1/attempts/1", false, false, true),
                Arguments.of("GET", "/api/v1/runs/r1/steps/api1/callbacks/1", false, false, true),
                Arguments.of("GET", "/api/v1/runs/r1/exchanges/INBOUND_REQUEST", false, false, true),
                Arguments.of("POST", "/api/v1/runs/r1/cancel", false, false, true),
                Arguments.of("POST", "/api/v1/runs/r1/resume", false, false, true),
                Arguments.of("POST", "/api/v1/runs/r1/steps/api1/resolve", false, false, true),
                Arguments.of("POST", "/api/v1/runs/r1/webhooks/e1/redeliver", false, false, true),
                Arguments.of("POST", "/api/v1/runs/erase", false, false, true),
                Arguments.of("GET", "/api/v1/admin/endpoints", false, true, true),
                Arguments.of("POST", "/api/v1/admin/endpoints", false, true, false),
                Arguments.of("PUT", "/api/v1/admin/endpoints/x", false, true, false),
                Arguments.of("DELETE", "/api/v1/admin/endpoints/x", false, true, false),
                Arguments.of("POST", "/api/v1/admin/endpoints/x/probe", false, true, false),
                Arguments.of("GET", "/api/v1/admin/endpoints/x/circuit", false, false, true),
                Arguments.of("POST", "/api/v1/admin/endpoints/x/circuit/reset", false, false, true),
                Arguments.of("GET", "/api/v1/admin/pipelines", false, true, true),
                Arguments.of("POST", "/api/v1/admin/pipelines", false, true, false),
                Arguments.of("PUT", "/api/v1/admin/pipelines/P/draft", false, true, false),
                Arguments.of("POST", "/api/v1/admin/pipelines/P/validate", false, true, false),
                Arguments.of("POST", "/api/v1/admin/pipelines/P/publish", false, true, false),
                Arguments.of("GET", "/api/v1/admin/pipelines/P/versions", false, true, true),
                Arguments.of("POST", "/api/v1/admin/pipelines/P/rollback", false, true, false),
                Arguments.of("POST", "/api/v1/admin/pipelines/P/disable", false, true, true),
                Arguments.of("POST", "/api/v1/admin/pipelines/P/enable", false, true, true));
    }

    private int status(String method, String path, String role) throws Exception {
        Http http = new Http(port).as(TestJwt.token("caller-" + role, role));
        HttpResponse<String> res = http.send(method, path, "{}", null);
        return res.statusCode();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("matrix")
    void rolesMatchTheMatrix(String method, String path, boolean run, boolean admin, boolean ops) throws Exception {
        assertAccess(status(method, path, Roles.RUN), run, method + " " + path + " as run");
        assertAccess(status(method, path, Roles.ADMIN), admin, method + " " + path + " as admin");
        assertAccess(status(method, path, Roles.OPS), ops, method + " " + path + " as ops");
    }

    private static void assertAccess(int status, boolean allowed, String what) {
        if (allowed) {
            assertThat(status).as(what).isNotIn(401, 403);
        } else {
            assertThat(status).as(what).isEqualTo(403);
        }
    }

    @Test
    void noTokenIs401WithBearerChallenge() throws Exception {
        HttpResponse<String> res = Http.anonymous(port).get("/api/v1/admin/pipelines");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
        assertThat(res.body()).contains("urn:apiorch:problem:unauthorized", "correlationId");
    }

    @Test
    void forbiddenIsProblemJson() throws Exception {
        HttpResponse<String> res = new Http(port).as(TestJwt.token("c", Roles.RUN)).get("/api/v1/admin/pipelines");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("urn:apiorch:problem:forbidden");
    }

    @Test
    void unknownApiPathWithAValidTokenIs404() throws Exception {
        HttpResponse<String> res = new Http(port).as(TestJwt.token("c", Roles.RUN)).get("/api/v1/nothing/here");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("urn:apiorch:problem:not-found");
    }

    @Test
    void auditColumnsUseThePrincipalClaim() throws Exception {
        HttpResponse<String> res = new Http(port).as(TestJwt.token("svc-admin-7", Roles.ADMIN)).postJson(
                "/api/v1/admin/endpoints", testsupport.web.EndpointJson.minimal("principal-check", "https://a.example.com"));
        assertThat(res.body()).contains("\"createdBy\":\"svc-admin-7\"");
    }
}
