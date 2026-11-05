package com.crisil.apiorch.http.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;

/**
 * Client-credentials tokens per endpoint and auth configuration (US-2.2 AC3, AC4). A token is reused until
 * {@code expires_in - 60 s}; concurrent callers share one in-flight fetch (single flight). The token request uses
 * {@code client_secret_basic} and is logged at DEBUG with endpoint and status only, never as a step attempt.
 */
@Component
public class OAuth2TokenCache {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenCache.class);
    static final Duration EARLY_REFRESH = Duration.ofSeconds(60);
    static final long DEFAULT_EXPIRES_IN_S = 300;

    record Token(String value, Instant refreshAt) {
    }

    private final Map<String, CompletableFuture<Token>> tokens = new ConcurrentHashMap<>();
    private final SecretResolver secrets;
    private final RestClient restClient;
    private final Clock clock;

    public OAuth2TokenCache(SecretResolver secrets, RestClient.Builder builder, Clock clock) {
        this.secrets = secrets;
        this.restClient = builder.clone().build();
        this.clock = clock;
    }

    /** The cache key: endpoint name plus the auth configuration, so a changed config never reuses a token. */
    static String key(String endpoint, EndpointRequest.Auth auth) {
        return endpoint + "#" + Integer.toHexString(auth.hashCode());
    }

    public String token(String endpoint, EndpointRequest.Auth auth) throws AuthFailureException {
        String key = key(endpoint, auth);
        while (true) {
            CompletableFuture<Token> mine = new CompletableFuture<>();
            CompletableFuture<Token> current = tokens.putIfAbsent(key, mine);
            if (current == null) {
                try {
                    mine.complete(fetch(endpoint, auth));
                } catch (AuthFailureException | RuntimeException e) {
                    tokens.remove(key, mine);
                    mine.completeExceptionally(e);
                }
                current = mine;
            }
            Token token;
            try {
                token = current.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthFailureException(AuthFailureException.TOKEN_FETCH_FAILED, "Interrupted fetching token");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AuthFailureException afe) {
                    throw afe;
                }
                throw new AuthFailureException(AuthFailureException.TOKEN_FETCH_FAILED, "Token fetch failed");
            }
            if (Instant.now(clock).isBefore(token.refreshAt())) {
                return token.value();
            }
            tokens.remove(key, current);
        }
    }

    /** Drops the cached token after the downstream API rejected it (AC4). */
    public void evict(String endpoint, EndpointRequest.Auth auth) {
        tokens.remove(key(endpoint, auth));
    }

    private Token fetch(String endpoint, EndpointRequest.Auth auth) throws AuthFailureException {
        String clientSecret = secrets.resolve(auth.clientSecretRef()).orElseThrow(() -> new AuthFailureException(
                AuthFailureException.SECRET_UNAVAILABLE, "Client secret reference cannot be resolved"));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        if (auth.scope() != null) {
            form.add("scope", auth.scope());
        }
        if (auth.audience() != null) {
            form.add("audience", auth.audience());
        }
        String basic = Base64.getEncoder().encodeToString(
                (auth.clientId() + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        JsonNode body;
        try {
            body = restClient.post().uri(auth.tokenUrl())
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            log.debug("token fetch failed endpoint={} error={}", endpoint, e.getClass().getSimpleName());
            throw new AuthFailureException(AuthFailureException.TOKEN_FETCH_FAILED, "Token endpoint failed");
        }
        String value = body != null ? body.path("access_token").asText(null) : null;
        if (value == null || value.isEmpty()) {
            throw new AuthFailureException(AuthFailureException.TOKEN_FETCH_FAILED, "Token response has no access_token");
        }
        long expiresIn = body.path("expires_in").asLong(DEFAULT_EXPIRES_IN_S);
        log.debug("token fetched endpoint={} expiresIn={}", endpoint, expiresIn);
        return new Token(value, Instant.now(clock).plusSeconds(expiresIn).minus(EARLY_REFRESH));
    }
}
