package com.crisil.apiorch.http.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;

/**
 * Applies one endpoint's auth to every request (US-2.2), as the innermost interceptor of that endpoint's own
 * {@code RestClient}, so it never leaks to another endpoint. A secret that cannot be resolved stops the request
 * before anything is sent (AC5). For OAuth2, a {@code 401} evicts the token, fetches a new one and repeats the call
 * once inside the same attempt (AC4); the capture interceptor (US-7.2) sits outside and sees only the final
 * exchange. {@link Listener#tokenRefreshed} lets the step executor record {@code TOKEN_REFRESHED} (US-4.3).
 */
public final class AuthInterceptor implements ClientHttpRequestInterceptor {

    /** Notified when a rejected token was refreshed and the call repeated. */
    public interface Listener {
        void tokenRefreshed(String endpoint);
    }

    private static final ThreadLocal<Listener> LISTENER = new ThreadLocal<>();

    private final String endpoint;
    private final EndpointRequest.Auth auth;
    private final SecretResolver secrets;
    private final OAuth2TokenCache tokens;

    public AuthInterceptor(String endpoint, EndpointRequest.Auth auth, SecretResolver secrets, OAuth2TokenCache tokens) {
        this.endpoint = endpoint;
        this.auth = auth;
        this.secrets = secrets;
        this.tokens = tokens;
    }

    /** Runs {@code call} with {@code listener} receiving refresh notifications from this thread's requests. */
    public static <T> T withListener(Listener listener, java.util.function.Supplier<T> call) {
        Listener previous = LISTENER.get();
        LISTENER.set(listener);
        try {
            return call.get();
        } finally {
            if (previous == null) {
                LISTENER.remove();
            } else {
                LISTENER.set(previous);
            }
        }
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        switch (auth.type()) {
            case NONE -> {
                return execution.execute(request, body);
            }
            case API_KEY -> request.getHeaders().set(auth.headerName(), secret(auth.secretRef()));
            case BEARER -> request.getHeaders().setBearerAuth(secret(auth.secretRef()));
            case BASIC -> request.getHeaders().set("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (auth.username() + ":" + secret(auth.passwordRef())).getBytes(StandardCharsets.UTF_8)));
            case OAUTH2_CLIENT_CREDENTIALS -> {
                return oauth2(request, body, execution);
            }
        }
        return execution.execute(request, body);
    }

    private ClientHttpResponse oauth2(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(tokens.token(endpoint, auth));
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
            return response;
        }
        response.close();
        tokens.evict(endpoint, auth);
        request.getHeaders().setBearerAuth(tokens.token(endpoint, auth));
        Listener listener = LISTENER.get();
        if (listener != null) {
            listener.tokenRefreshed(endpoint);
        }
        ClientHttpResponse repeated = execution.execute(request, body);
        if (repeated.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            repeated.close();
            throw new AuthFailureException(AuthFailureException.TOKEN_REJECTED,
                    "Endpoint '" + endpoint + "' rejected a freshly fetched token");
        }
        return repeated;
    }

    private String secret(String ref) throws AuthFailureException {
        return secrets.resolve(ref).orElseThrow(() -> new AuthFailureException(AuthFailureException.SECRET_UNAVAILABLE,
                "Secret reference for endpoint '" + endpoint + "' cannot be resolved"));
    }
}
