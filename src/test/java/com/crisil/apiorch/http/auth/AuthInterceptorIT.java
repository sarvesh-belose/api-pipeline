package com.crisil.apiorch.http.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import com.crisil.apiorch.endpoint.dto.EndpointRequest.Auth;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.AuthType;

import testsupport.time.MutableClock;

/**
 * US-2.2 AC1, AC3–AC5 against WireMock: the header per type, no request when a secret is missing, OAuth2 token
 * reuse, single flight, expiry, one refresh on 401, and token endpoint failures. Plain Java, no Spring context.
 */
class AuthInterceptorIT {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension idp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final Map<String, String> secretValues = Map.of("env:APIORCH_SECRET_KEY", "k-123",
            "env:APIORCH_SECRET_PWD", "pw", "env:APIORCH_SECRET_CLIENT", "client-secret");
    private final SecretResolver secrets = ref -> Optional.ofNullable(secretValues.get(ref));
    private final MutableClock clock = MutableClock.at("2026-11-16T09:00:00Z");
    private OAuth2TokenCache tokens;

    @BeforeEach
    void setUp() {
        tokens = new OAuth2TokenCache(secrets, RestClient.builder(), clock);
        api.stubFor(get("/x").willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    private static Auth auth(AuthType type, String headerName, String secretRef, String username, String passwordRef,
            String tokenUrl, String clientId, String clientSecretRef) {
        return new Auth(type, headerName, secretRef, username, passwordRef, tokenUrl, clientId, clientSecretRef,
                "accounts.write", null, null, null, null);
    }

    private Auth oauth2() {
        return auth(AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, null, null,
                "http://localhost:" + idp.getPort() + "/token", "apiorch", "env:APIORCH_SECRET_CLIENT");
    }

    private RestClient client(String endpoint, Auth auth) {
        return RestClient.builder().baseUrl("http://localhost:" + api.getPort())
                .requestInterceptor(new AuthInterceptor(endpoint, auth, secrets, tokens)).build();
    }

    private int call(RestClient client) {
        return client.get().uri("/x").exchange((req, res) -> res.getStatusCode().value());
    }

    private void tokenEndpointIssues(String token, int expiresIn) {
        idp.stubFor(post("/token").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + "}")));
    }

    @Test
    void staticTypesSetTheirHeaders() {
        call(client("a", auth(AuthType.API_KEY, "X-Api-Key", "env:APIORCH_SECRET_KEY", null, null, null, null, null)));
        api.verify(getRequestedFor(urlEqualTo("/x")).withHeader("X-Api-Key", equalTo("k-123")));
        api.resetRequests();
        call(client("b", auth(AuthType.BEARER, null, "env:APIORCH_SECRET_KEY", null, null, null, null, null)));
        api.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer k-123")));
        api.resetRequests();
        call(client("c", auth(AuthType.BASIC, null, null, "apiorch", "env:APIORCH_SECRET_PWD", null, null, null)));
        api.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Basic YXBpb3JjaDpwdw==")));
        api.resetRequests();
        call(client("d", Auth.none()));
        api.verify(getRequestedFor(urlEqualTo("/x")).withoutHeader("Authorization"));
    }

    @Test
    void missingSecretSendsNothing() {
        RestClient c = client("a", auth(AuthType.API_KEY, "X-Api-Key", "env:APIORCH_SECRET_GONE", null, null, null,
                null, null));
        assertThatThrownBy(() -> call(c)).isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(AuthFailureException.class)
                .satisfies(e -> assertThat(((AuthFailureException) e.getCause()).code()).isEqualTo("SECRET_UNAVAILABLE"));
        assertThat(api.findAll(getRequestedFor(urlEqualTo("/x")))).isEmpty();
    }

    @Test
    void oauth2TokenIsFetchedOnceAndReusedUntilNearExpiry() {
        tokenEndpointIssues("t1", 600);
        RestClient c = client("core", oauth2());
        call(c);
        call(c);
        idp.verify(1, postRequestedFor(urlEqualTo("/token"))
                .withHeader("Authorization", equalTo("Basic YXBpb3JjaDpjbGllbnQtc2VjcmV0"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("grant_type=client_credentials")));
        api.verify(2, getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer t1")));

        clock.advance(Duration.ofSeconds(541));
        tokenEndpointIssues("t2", 600);
        call(c);
        api.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer t2")));
        idp.verify(2, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void concurrentCallersShareOneTokenFetch() throws Exception {
        idp.stubFor(post("/token").willReturn(aResponse().withStatus(200).withFixedDelay(300)
                .withHeader("Content-Type", "application/json").withBody("{\"access_token\":\"t\",\"expires_in\":600}")));
        RestClient c = client("core", oauth2());
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return call(c);
            }));
        }
        start.countDown();
        for (CompletableFuture<Integer> f : calls) {
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        idp.verify(1, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void rejectedTokenIsRefreshedOnceAndTheCallRepeated() {
        tokenEndpointIssues("stale", 600);
        RestClient c = client("core", oauth2());
        call(c);
        api.stubFor(get("/x").inScenario("401").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401)).willSetStateTo("fresh"));
        api.stubFor(get("/x").inScenario("401").whenScenarioStateIs("fresh").willReturn(aResponse().withStatus(200)));
        tokenEndpointIssues("fresh", 600);
        List<String> refreshed = new ArrayList<>();
        int status = AuthInterceptor.withListener(refreshed::add, () -> call(c));
        assertThat(status).isEqualTo(200);
        assertThat(refreshed).containsExactly("core");
        api.verify(getRequestedFor(urlEqualTo("/x")).withHeader("Authorization", equalTo("Bearer fresh")));
    }

    @Test
    void secondRejectionIsTokenRejected() {
        tokenEndpointIssues("t", 600);
        api.stubFor(get("/x").willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> call(client("core", oauth2()))).hasCauseInstanceOf(AuthFailureException.class)
                .satisfies(e -> assertThat(((AuthFailureException) e.getCause()).code()).isEqualTo("TOKEN_REJECTED"));
        idp.verify(2, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void tokenEndpointFailuresAreTokenFetchFailed() {
        idp.stubFor(post("/token").willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> call(client("core", oauth2()))).hasCauseInstanceOf(AuthFailureException.class)
                .satisfies(e -> assertThat(((AuthFailureException) e.getCause()).code()).isEqualTo("TOKEN_FETCH_FAILED"));
        assertThat(api.findAll(getRequestedFor(urlEqualTo("/x")))).isEmpty();

        idp.stubFor(post("/token").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"token_type\":\"bearer\"}")));
        assertThatThrownBy(() -> call(client("core2", oauth2()))).hasCauseInstanceOf(AuthFailureException.class);

        Auth noSecret = auth(AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, null, null,
                "http://localhost:" + idp.getPort() + "/token", "apiorch", "env:APIORCH_SECRET_NONE");
        assertThatThrownBy(() -> call(client("core3", noSecret))).hasCauseInstanceOf(AuthFailureException.class)
                .satisfies(e -> assertThat(((AuthFailureException) e.getCause()).code()).isEqualTo("SECRET_UNAVAILABLE"));
    }

    @Test
    void missingExpiresInUsesTheDefault() {
        idp.stubFor(post("/token").willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"t\"}")));
        RestClient c = client("core", oauth2());
        call(c);
        clock.advance(Duration.ofSeconds(OAuth2TokenCache.DEFAULT_EXPIRES_IN_S - 61));
        call(c);
        idp.verify(1, postRequestedFor(urlEqualTo("/token")));
    }
}
