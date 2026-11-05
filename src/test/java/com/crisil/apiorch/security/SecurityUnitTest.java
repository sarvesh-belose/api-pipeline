package com.crisil.apiorch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import com.crisil.apiorch.config.ApiorchProperties.Security.Mode;

/** US-1.2 AC6 and AC7, plus the CIDR check, without a Spring context. */
class SecurityUnitTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    private static List<String> names(java.util.Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void rolesComeFromFlatNestedOrSpaceSeparatedClaims() {
        RolesClaimConverter flat = new RolesClaimConverter("roles", "sub");
        var token = flat.convert(jwt(Map.of("sub", "crm", "roles", List.of("apiorch.run", " "))));
        assertThat(token.getName()).isEqualTo("crm");
        assertThat(names(token.getAuthorities())).containsExactly("ROLE_apiorch.run");

        RolesClaimConverter nested = new RolesClaimConverter("realm_access.roles", "preferred_username");
        var nestedToken = nested.convert(jwt(Map.of("sub", "id-1", "preferred_username", "asha",
                "realm_access", Map.of("roles", List.of("apiorch.ops")))));
        assertThat(nestedToken.getName()).isEqualTo("asha");
        assertThat(names(nestedToken.getAuthorities())).containsExactly("ROLE_apiorch.ops");

        assertThat(names(flat.authorities(Map.of("roles", "apiorch.run apiorch.ops"))))
                .containsExactly("ROLE_apiorch.run", "ROLE_apiorch.ops");
        assertThat(nested.authorities(Map.of("realm_access", "not-an-object"))).isEmpty();
        assertThat(flat.authorities(Map.of())).isEmpty();
        assertThat(nested.convert(jwt(Map.of("sub", "fallback"))).getName()).isEqualTo("fallback");
    }

    @Test
    void devModeNeedsTheLocalProfile() {
        for (String[] profiles : new String[][] {{"uat"}, {"prod"}, {}}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profiles);
            assertThatThrownBy(() -> SecurityConfig.requireLocalForDevMode(Mode.DEV, env))
                    .hasMessageContaining("only with the 'local' profile");
        }
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        SecurityConfig.requireLocalForDevMode(Mode.DEV, local);
        SecurityConfig.requireLocalForDevMode(Mode.JWT, new MockEnvironment());
    }

    @Test
    void devUserHasAllRoles() throws Exception {
        try {
            new SecurityConfig.DevUserFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    new MockFilterChain());
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getName()).isEqualTo("dev-user");
            assertThat(names(new java.util.ArrayList<>(auth.getAuthorities()))).containsExactlyInAnyOrder(
                    "ROLE_apiorch.run", "ROLE_apiorch.admin", "ROLE_apiorch.ops");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void cidrManagerMatchesRemoteAddress() {
        CidrAuthorizationManager manager = new CidrAuthorizationManager(List.of("10.0.0.0/8", "::1/128"));
        MockHttpServletRequest inside = new MockHttpServletRequest();
        inside.setRemoteAddr("10.1.2.3");
        MockHttpServletRequest outside = new MockHttpServletRequest();
        outside.setRemoteAddr("192.168.1.1");
        assertThat(manager.check(() -> null, new RequestAuthorizationContext(inside)).isGranted()).isTrue();
        assertThat(manager.check(() -> null, new RequestAuthorizationContext(outside)).isGranted()).isFalse();
    }
}
