package com.crisil.apiorch.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import org.junit.jupiter.api.extension.ExtendWith;

import testsupport.security.TestJwt;
import testsupport.web.Http;

/** US-1.2 AC2 and AC6: invalid tokens are 401 without the token in the log; roles come from a nested claim. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "apiorch.security.roles-claim=realm_access.roles")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class JwtValidationIT {

    @Value("${local.server.port}") int port;

    private HttpResponse<String> call(String token) throws Exception {
        return new Http(port).as(token).get("/api/v1/admin/pipelines");
    }

    private static String nested(TestJwt.Builder b) {
        return b.claim("realm_access", Map.of("roles", List.of(Roles.ADMIN))).sign();
    }

    @Test
    void nestedRolesClaimIsMapped() throws Exception {
        assertThat(call(nested(TestJwt.builder("nested-admin"))).statusCode()).isEqualTo(200);
        assertThat(call(TestJwt.token("flat", Roles.ADMIN)).statusCode()).isEqualTo(403);
    }

    @Test
    void invalidTokensAreRejectedAndNotLogged(CapturedOutput output) throws Exception {
        String expired = nested(TestJwt.builder("a").expiredSecondsAgo(120));
        String unknownKey = nested(TestJwt.builder("a").signedByUnknownKey());
        String wrongIssuer = nested(TestJwt.builder("a").issuer("https://evil.example.com"));
        String wrongAudience = nested(TestJwt.builder("a").audience("someone-else"));
        for (String token : new String[] {expired, unknownKey, wrongIssuer, wrongAudience, "not-a-jwt"}) {
            HttpResponse<String> res = call(token);
            assertThat(res.statusCode()).isEqualTo(401);
            assertThat(res.body()).contains("urn:apiorch:problem:unauthorized");
            assertThat(output.getAll()).doesNotContain(token);
        }
        assertThat(output.getAll()).contains("authentication failed path=/api/v1/admin/pipelines");
    }
}
