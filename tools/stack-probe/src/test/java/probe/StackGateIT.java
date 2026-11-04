package probe;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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
import java.util.UUID;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One context, one PostgreSQL 16 container; each test exercises one gate item at runtime. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StackGateIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine").withInitScript("init-roles.sql");

    @Autowired Environment env;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RunRowRepo runs;
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

    private HttpResponse<String> getUrl(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString());
    }

    @Test
    void securedAsyncRunEndpoint() throws Exception {
        HttpResponse<String> ok = post(base() + "/api/v1/pipelines/DEMO/runs", "{\"name\":\"Asha\"}", jwt("apiorch.run"));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("COMPLETED");
        assertThat(post(base() + "/api/v1/pipelines/DEMO/runs", "{}", null).statusCode()).isEqualTo(401);
        assertThat(post(base() + "/api/v1/pipelines/DEMO/runs", "{}", jwt("apiorch.ops")).statusCode()).isEqualTo(403);
    }

    @Test
    void callbackChainIgnoresUnrelatedBearer() throws Exception {
        assertThat(post(base() + "/api/v1/callbacks/r1/api4?t=abc", "{}", "not-a-jwt").statusCode()).isEqualTo(200);
    }

    @Test
    void actuatorChainOnManagementPort() throws Exception {
        assertThat(getUrl(mgmt() + "/actuator/health").statusCode()).isEqualTo(200);
        assertThat(getUrl(mgmt() + "/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(getUrl(mgmt() + "/actuator/prometheus").statusCode()).isEqualTo(403);
    }

    @Test
    void openApiDocs() throws Exception {
        HttpResponse<String> docs = getUrl(base() + "/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("\"openapi\"");
    }

    @Test
    void leaseSqlWithDatabaseTimeAndJpaMapping() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO pipeline_run(id, status, owner_instance, lease_epoch, lease_until, meta) "
                        + "VALUES (:id, 'ACCEPTED', :me, 1, CURRENT_TIMESTAMP + make_interval(secs => :secs), CAST(:meta AS jsonb))")
                .param("id", id).param("me", "node-a").param("secs", 30).param("meta", "{\"k\":1}").update();

        Integer fenced = tx.execute(s -> jdbc.sql("SELECT count(*) FROM (SELECT id FROM pipeline_run "
                        + "WHERE id = :id AND owner_instance = :me AND lease_epoch = :e FOR UPDATE) t")
                .param("id", id).param("me", "node-a").param("e", 1).query(Integer.class).single());
        assertThat(fenced).isEqualTo(1);

        jdbc.sql("UPDATE pipeline_run SET lease_until = CURRENT_TIMESTAMP - make_interval(secs => 1) WHERE id = :id")
                .param("id", id).update();
        int claimed = jdbc.sql("UPDATE pipeline_run SET owner_instance = :me, lease_epoch = lease_epoch + 1, "
                        + "lease_until = CURRENT_TIMESTAMP + make_interval(secs => :secs), wakeup_requested = false "
                        + "WHERE id = :id AND ((owner_instance IS NULL AND wakeup_requested) "
                        + "OR (owner_instance IS NOT NULL AND lease_until < CURRENT_TIMESTAMP))")
                .param("me", "node-b").param("secs", 30).param("id", id).update();
        assertThat(claimed).isEqualTo(1);

        RunRow row = runs.findById(id).orElseThrow();
        assertThat(row.leaseEpoch).isEqualTo(2);
        assertThat(row.leaseUntil).isAfter(Instant.now());
        assertThat(row.createdAt).isBefore(Instant.now().plusSeconds(1));
        assertThat(row.meta).contains("\"k\"");
    }

    @Test
    void appendOnlyGrantAndMonthPartition() {
        UUID run = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL ROLE apiorch_app").update();
            jdbc.sql("INSERT INTO run_event(run_id, seq, occurred_at, event_type, details) "
                    + "VALUES (:r, 1, CURRENT_TIMESTAMP, 'RUN_ACCEPTED', '{}'::jsonb)").param("r", run).update();
        });
        String partition = jdbc.sql("SELECT tableoid::regclass::text FROM run_event WHERE run_id = :r")
                .param("r", run).query(String.class).single();
        assertThat(partition).startsWith("run_event_2").isNotEqualTo("run_event_default");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL ROLE apiorch_app").update();
            jdbc.sql("UPDATE run_event SET event_type = 'X' WHERE run_id = :r").param("r", run).update();
        })).hasRootCauseMessage("ERROR: permission denied for table run_event"); // Spring maps 42501 to BadSqlGrammarException
    }

    @Test
    void shedLockedSchedulerRuns() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ProbeApplication.Sweeper.RUNS.get() > 1);
        assertThat(jdbc.sql("SELECT count(*) FROM shedlock WHERE name = 'probe-sweeper'").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void resilienceDecoratorsOverClonedRestClient() throws Exception {
        WireMockServer wm = new WireMockServer(options().dynamicPort());
        wm.start();
        ExecutorService io = Executors.newFixedThreadPool(4);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            wm.stubFor(get("/kyc").inScenario("flaky").whenScenarioStateIs(STARTED)
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("one"));
            wm.stubFor(get("/kyc").inScenario("flaky").whenScenarioStateIs("one")
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("two"));
            wm.stubFor(get("/kyc").inScenario("flaky").whenScenarioStateIs("two")
                    .willReturn(okJson("{\"ref\":\"K-9\"}")));

            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
            factory.setReadTimeout(Duration.ofSeconds(2));
            RestClient client = restClientBuilder.clone().baseUrl(wm.baseUrl()).requestFactory(factory).build();

            CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(cbRegistry).bindTo(meters);
            CircuitBreaker cb = cbRegistry.circuitBreaker("kyc-svc");
            Bulkhead bh = Bulkhead.of("kyc-svc", BulkheadConfig.custom().maxConcurrentCalls(5).maxWaitDuration(Duration.ZERO).build());
            Retry retry = Retry.of("kyc-svc", RetryConfig.<Integer>custom().maxAttempts(3)
                    .waitDuration(Duration.ofMillis(50)).retryOnResult(status -> status >= 500).build());

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
