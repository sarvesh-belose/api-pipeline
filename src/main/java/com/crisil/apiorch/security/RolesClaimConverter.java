package com.crisil.apiorch.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps the roles claim to authorities {@code ROLE_<value>} and names the principal from the principal claim (US-1.2
 * AC6). The roles claim may be a dotted path into nested objects, for example {@code realm_access.roles}; it may
 * hold a list or a space-separated string.
 */
public class RolesClaimConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String[] rolesPath;
    private final String principalClaim;

    public RolesClaimConverter(String rolesClaim, String principalClaim) {
        this.rolesPath = rolesClaim.split("\\.");
        this.principalClaim = principalClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String name = jwt.getClaimAsString(principalClaim);
        return new JwtAuthenticationToken(jwt, authorities(jwt.getClaims()), name != null ? name : jwt.getSubject());
    }

    Collection<GrantedAuthority> authorities(Map<String, Object> claims) {
        Object node = claims;
        for (String part : rolesPath) {
            if (!(node instanceof Map<?, ?> map)) {
                return List.of();
            }
            node = map.get(part);
        }
        List<GrantedAuthority> out = new ArrayList<>();
        if (node instanceof Collection<?> values) {
            values.forEach(v -> add(out, v));
        } else if (node instanceof String s) {
            for (String v : s.split(" ")) {
                add(out, v);
            }
        }
        return out;
    }

    private static void add(List<GrantedAuthority> out, Object value) {
        if (value != null && !value.toString().isBlank()) {
            out.add(new SimpleGrantedAuthority("ROLE_" + value.toString().strip()));
        }
    }
}
