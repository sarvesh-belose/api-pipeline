package com.crisil.apiorch.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.util.AntPathMatcher;

import com.crisil.apiorch.observability.ApiorchMetrics;

import com.crisil.apiorch.observability.Problems;

/**
 * 401 and 403 as problem+json with the correlation ID (US-1.2). The 401 keeps the RFC 6750
 * {@code WWW-Authenticate: Bearer} header. Failures are logged at WARN with path, reason and client IP; the token
 * itself is never logged (AC2).
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemSecurityHandlers.class);

    /** The detail reads that return bodies (US-7.4 AC9); a refusal is counted, nothing is recorded on the run. */
    static final java.util.List<String> PAYLOAD_DETAIL = java.util.List.of("/api/v1/runs/*/steps/*/attempts/*",
            "/api/v1/runs/*/steps/*/callbacks/*", "/api/v1/runs/*/exchanges/*");

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final ObjectMapper json;
    private final ApiorchMetrics metrics;
    private final AntPathMatcher paths = new AntPathMatcher();

    public ProblemSecurityHandlers(ObjectMapper json, ApiorchMetrics metrics) {
        this.json = json;
        this.metrics = metrics;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        bearer.commence(request, response, ex);
        log.warn("authentication failed path={} reason={} clientIp={}", request.getRequestURI(), ex.getMessage(),
                request.getRemoteAddr());
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "A valid bearer token is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        log.warn("access denied path={} clientIp={}", request.getRequestURI(), request.getRemoteAddr());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (PAYLOAD_DETAIL.stream().anyMatch(p -> paths.match(p, path))) {
            metrics.payloadAccessDenied();
        }
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Your roles do not allow this request.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String slug,
            String detail) throws IOException {
        ProblemDetail problem = Problems.complete(Problems.of(status, slug, detail), request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
