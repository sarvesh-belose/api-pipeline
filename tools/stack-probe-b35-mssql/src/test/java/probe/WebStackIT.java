package probe;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Boot 3.5 web, security, actuator, springdoc and Resilience4j checks, on the H2 fast tier. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebStackIT {

    @Autowired Environment env;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired MeterRegistry meters;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://localhost:" + env.getProperty("local.server.port");
    }

    private String mgmt() {
        return "http://localhost:" + env.getProperty("local.management.port");
    }

    private static String jwt(String... roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("crm-system").claim("roles", List.of(roles))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signed.sign(new MACSigner("0123456789abcdef0123456789abcdef".getBytes()));
        return signed.serialize();
    }

    private HttpResponse<String> post(String url, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private int status(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString()).statusCode();
    }

    @Test
    void securedAsyncRunEndpoint() throws Exception {
        HttpResponse<String> ok = post(base() + "/api/v1/pipelines/DEMO/runs", "{\"name\":\"Asha\"}", jwt("apiorch.run"));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("COMPLETED");
        assertThat(post(base() + "/api/v1/pipelines/DEMO/runs", "{}", null).statusCode()).isEqualTo(401);
        assertThat(post(base() + "/api/v1/pipelines/DEMO/runs", "{}", jwt("apiorch.ops")).statusCode()).isEqualTo(403);
    }

    @Autowired com.fasterxml.jackson.databind.ObjectMapper bootMapper;

    @Test
    void bootObjectMapperUnknownPropertyDefault() {
        System.out.println("G3|jackson2 Boot ObjectMapper FAIL_ON_UNKNOWN_PROPERTIES="
                + bootMapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Test
    void callbackChainIgnoresUnrelatedBearer() throws Exception {
        assertThat(post(base() + "/api/v1/callbacks/r1/api4?t=abc", "{}", "not-a-jwt").statusCode()).isEqualTo(200);
    }

    @Test
    void actuatorAndOpenApi() throws Exception {
        assertThat(status(mgmt() + "/actuator/health/readiness")).isEqualTo(200);
        int prometheus = status(mgmt() + "/actuator/prometheus");
        System.out.println("G3|security|unauthenticated /actuator/prometheus -> " + prometheus);
        assertThat(prometheus).isIn(401, 403);
        assertThat(status(base() + "/v3/api-docs")).isEqualTo(200);
    }

    @Test
    void resilienceDecoratorsOverClonedRestClient() throws Exception {
        WireMockServer wm = new WireMockServer(options().dynamicPort());
        wm.start();
        ExecutorService io = Executors.newFixedThreadPool(4);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            wm.stubFor(get("/kyc").inScenario("f").whenScenarioStateIs(STARTED).willReturn(aResponse().withStatus(503)).willSetStateTo("1"));
            wm.stubFor(get("/kyc").inScenario("f").whenScenarioStateIs("1").willReturn(aResponse().withStatus(503)).willSetStateTo("2"));
            wm.stubFor(get("/kyc").inScenario("f").whenScenarioStateIs("2").willReturn(okJson("{\"ref\":\"K-9\"}")));
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
            factory.setReadTimeout(Duration.ofSeconds(2));
            RestClient client = restClientBuilder.clone().baseUrl(wm.baseUrl()).requestFactory(factory).build();
            CircuitBreakerRegistry reg = CircuitBreakerRegistry.ofDefaults();
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(reg).bindTo(meters);
            CircuitBreaker cb = reg.circuitBreaker("kyc-svc");
            Bulkhead bh = Bulkhead.of("kyc-svc", BulkheadConfig.custom().maxConcurrentCalls(5).maxWaitDuration(Duration.ZERO).build());
            Retry retry = Retry.of("kyc-svc", RetryConfig.<Integer>custom().maxAttempts(3)
                    .waitDuration(Duration.ofMillis(50)).retryOnResult(s -> s >= 500).build());
            Supplier<CompletionStage<Integer>> call = () -> CompletableFuture.supplyAsync(
                    () -> client.get().uri("/kyc").exchange((req, res) -> res.getStatusCode().value()), io);
            Supplier<CompletionStage<Integer>> decorated = Retry.decorateCompletionStage(retry, scheduler,
                    CircuitBreaker.decorateCompletionStage(cb, Bulkhead.decorateCompletionStage(bh, call)));
            assertThat(decorated.get().toCompletableFuture().get(10, TimeUnit.SECONDS)).isEqualTo(200);
            wm.verify(3, getRequestedFor(urlEqualTo("/kyc")));
            assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "kyc-svc").gauges()).isNotEmpty();
        } finally {
            io.shutdownNow();
            scheduler.shutdownNow();
            wm.stop();
        }
    }
}
