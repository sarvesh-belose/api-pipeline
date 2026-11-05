package com.crisil.apiorch.security;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** Allows a request only from {@code apiorch.security.metrics-allowed-cidrs} (US-1.2 AC5), for Prometheus. */
public class CidrAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final List<IpAddressMatcher> allowed;

    public CidrAuthorizationManager(List<String> cidrs) {
        this.allowed = cidrs.stream().map(IpAddressMatcher::new).toList();
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        String remote = context.getRequest().getRemoteAddr();
        return new AuthorizationDecision(allowed.stream().anyMatch(m -> m.matches(remote)));
    }
}
