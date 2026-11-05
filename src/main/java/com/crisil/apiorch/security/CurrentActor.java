package com.crisil.apiorch.security;

import java.security.Principal;

import org.springframework.stereotype.Component;

/**
 * Who is acting, for {@code created_by}, {@code published_by} and {@code actor} (US-1.2). The principal name is
 * the {@code apiorch.security.principal-claim} of the JWT (default {@code sub}), set by {@link RolesClaimConverter};
 * {@code dev-user} in the local dev mode; {@code anonymous} only where no authentication exists.
 */
@Component
public class CurrentActor {

    public static final String ANONYMOUS = "anonymous";

    public String of(Principal principal) {
        return principal != null && principal.getName() != null && !principal.getName().isBlank()
                ? principal.getName() : ANONYMOUS;
    }
}
