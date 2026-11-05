package com.crisil.apiorch.security;

import static com.crisil.apiorch.security.Roles.ADMIN;
import static com.crisil.apiorch.security.Roles.OPS;
import static com.crisil.apiorch.security.Roles.RUN;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.config.ApiorchProperties.Security.Mode;

/**
 * The ordered filter chains of US-1.2, Spring Security 6.5 lambda DSL: (1) callbacks, authenticated by their
 * signed token, never by JWT; (2) actuator on the management port; (3) API docs in {@code local}; (4) the business
 * API with the README §9 role matrix. Unknown {@code /api/**} routes reach MVC for a 404 (AC8).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    static final String DEV_USER = "dev-user";

    private final ApiorchProperties.Security settings;
    private final ProblemSecurityHandlers handlers;

    public SecurityConfig(ApiorchProperties properties, ProblemSecurityHandlers handlers, Environment environment) {
        this.settings = properties.security();
        this.handlers = handlers;
        requireLocalForDevMode(settings.mode(), environment);
    }

    /** AC7: dev mode outside {@code local} must never start. */
    static void requireLocalForDevMode(Mode mode, Environment environment) {
        if (mode == Mode.DEV) {
            if (!environment.acceptsProfiles(Profiles.of("local"))) {
                throw new IllegalStateException("apiorch.security.mode=dev is allowed only with the 'local' profile; "
                        + "active profiles: " + List.of(environment.getActiveProfiles()));
            }
            log.warn("DEV SECURITY MODE – NOT FOR PRODUCTION: every request is {} with all roles", DEV_USER);
        }
    }

    @Bean
    @Order(1)
    SecurityFilterChain callbackChain(HttpSecurity http) throws Exception {
        return stateless(http.securityMatcher("/api/v1/callbacks/**"))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        return stateless(http.securityMatcher(EndpointRequest.toAnyEndpoint()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers(EndpointRequest.to("prometheus"))
                        .access(new CidrAuthorizationManager(settings.metricsAllowedCidrs()))
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .build();
    }

    @Bean
    @Order(3)
    @Profile("local")
    SecurityFilterChain apiDocsChain(HttpSecurity http) throws Exception {
        return stateless(http.securityMatcher("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        stateless(http).authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/v1/pipelines/*/runs").hasRole(RUN)
                .requestMatchers(HttpMethod.GET, "/api/v1/runs/*/steps/*/attempts/*", "/api/v1/runs/*/steps/*/callbacks/*",
                        "/api/v1/runs/*/exchanges/*").hasRole(OPS)
                .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/cancel", "/api/v1/runs/*/resume",
                        "/api/v1/runs/*/steps/*/resolve", "/api/v1/runs/*/webhooks/*/redeliver", "/api/v1/runs/erase")
                .hasRole(OPS)
                .requestMatchers(HttpMethod.GET, "/api/v1/runs", "/api/v1/runs/**").hasAnyRole(RUN, OPS)
                .requestMatchers("/api/v1/admin/endpoints/*/circuit", "/api/v1/admin/endpoints/*/circuit/**").hasRole(OPS)
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/pipelines/*/disable", "/api/v1/admin/pipelines/*/enable")
                .hasAnyRole(ADMIN, OPS)
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/**").hasAnyRole(ADMIN, OPS)
                .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                .requestMatchers("/api/**").authenticated()
                .requestMatchers("/error").permitAll()
                .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        if (settings.mode() == Mode.DEV) {
            http.addFilterBefore(new DevUserFilter(), AuthorizationFilter.class);
        } else {
            http.oauth2ResourceServer(o -> o
                    .jwt(j -> j.jwtAuthenticationConverter(
                            new RolesClaimConverter(settings.rolesClaim(), settings.principalClaim())))
                    .authenticationEntryPoint(handlers)
                    .accessDeniedHandler(handlers));
        }
        return http.build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    /** {@code local} dev mode: every request is {@code dev-user} with all three roles (AC7). */
    static final class DevUserFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    DEV_USER, null, List.of(new SimpleGrantedAuthority("ROLE_" + RUN),
                            new SimpleGrantedAuthority("ROLE_" + ADMIN), new SimpleGrantedAuthority("ROLE_" + OPS))));
            chain.doFilter(request, response);
        }
    }
}
