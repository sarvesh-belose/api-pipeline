package com.crisil.apiorch.security;

import java.security.Principal;

import org.springframework.stereotype.Component;

/**
 * Who is acting, for {@code created_by} and audit fields. Until US-1.2 adds the JWT resource server this is the
 * request principal when there is one and {@code anonymous} otherwise; US-1.2 switches it to the configured
 * principal claim ({@code apiorch.security.principal-claim}, default {@code sub}).
 */
@Component
public class CurrentActor {

    public static final String ANONYMOUS = "anonymous";

    public String of(Principal principal) {
        return principal != null && principal.getName() != null && !principal.getName().isBlank()
                ? principal.getName() : ANONYMOUS;
    }
}
