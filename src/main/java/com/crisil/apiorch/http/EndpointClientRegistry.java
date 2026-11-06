package com.crisil.apiorch.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import com.crisil.apiorch.endpoint.EndpointChangedEvent;
import com.crisil.apiorch.endpoint.EndpointConfig;
import com.crisil.apiorch.endpoint.EndpointEntity;
import com.crisil.apiorch.endpoint.EndpointMapper;
import com.crisil.apiorch.endpoint.EndpointRepository;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.AuthType;
import com.crisil.apiorch.endpoint.dto.EndpointRequest.HttpVersion;
import com.crisil.apiorch.http.auth.AuthInterceptor;
import com.crisil.apiorch.http.auth.OAuth2TokenCache;
import com.crisil.apiorch.http.auth.SecretResolver;

/**
 * The live {@link EndpointRuntime} per endpoint name (US-2.1 AC4). A change on this node rebuilds the runtime after
 * commit; a scheduled refresh compares {@code row_version}s so changes made on other nodes apply within
 * {@code apiorch.endpoints.refresh-interval-ms}. Calls already in flight keep the runtime they started with.
 */
@Component
public class EndpointClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(EndpointClientRegistry.class);

    private final Map<String, EndpointRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<String, java.time.Instant> circuitSince = new ConcurrentHashMap<>();
    private final EndpointRepository repository;
    private final EndpointMapper mapper;
    private final RestClient.Builder restClientBuilder;
    private final SecretResolver secrets;
    private final OAuth2TokenCache tokens;
    private final long maxResponseBytes;
    private final java.time.Clock clock;

    public EndpointClientRegistry(EndpointRepository repository, EndpointMapper mapper,
            RestClient.Builder restClientBuilder, SecretResolver secrets, OAuth2TokenCache tokens,
            com.crisil.apiorch.config.ApiorchProperties properties, java.time.Clock clock) {
        this.clock = clock;
        this.repository = repository;
        this.mapper = mapper;
        this.restClientBuilder = restClientBuilder;
        this.secrets = secrets;
        this.tokens = tokens;
        this.maxResponseBytes = properties.run().maxResponseBytes();
    }

    /** The current runtime, loading it on first use. Empty if the endpoint does not exist. */
    public Optional<EndpointRuntime> get(String name) {
        EndpointRuntime runtime = runtimes.get(name);
        if (runtime != null) {
            return Optional.of(runtime);
        }
        return reload(name);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChange(EndpointChangedEvent event) {
        reload(event.name());
    }

    @Scheduled(fixedDelayString = "${apiorch.endpoints.refresh-interval-ms:10000}",
            initialDelayString = "${apiorch.endpoints.refresh-interval-ms:10000}")
    void refresh() {
        try {
            Map<String, Long> stored = new HashMap<>();
            repository.findAllVersions().forEach(v -> stored.put(v.getName(), v.getRowVersion()));
            runtimes.keySet().removeIf(name -> {
                boolean gone = !stored.containsKey(name);
                if (gone) {
                    log.info("endpoint runtime removed name={}", name);
                }
                return gone;
            });
            stored.forEach((name, version) -> {
                EndpointRuntime current = runtimes.get(name);
                if (current != null && current.version() != version) {
                    reload(name);
                }
            });
        } catch (RuntimeException e) {
            log.warn("endpoint refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    private Optional<EndpointRuntime> reload(String name) {
        Optional<EndpointEntity> entity = repository.findByName(name);
        if (entity.isEmpty()) {
            runtimes.remove(name);
            return Optional.empty();
        }
        EndpointRuntime runtime = build(mapper.toConfig(entity.get()));
        runtimes.put(name, runtime);
        log.info("endpoint runtime built name={} version={}", name, runtime.version());
        return Optional.of(runtime);
    }

    EndpointRuntime build(EndpointConfig config) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .version(config.httpVersion() == HttpVersion.HTTP_2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(config.readTimeoutMs()));
        // the exchange recorder is outermost, so it sees only the final exchange after any auth retry (US-7.2)
        RestClient.Builder builder = restClientBuilder.clone().requestFactory(factory)
                .requestInterceptor(new ExchangeRecorder(maxResponseBytes));
        if (config.authType() != AuthType.NONE) {
            builder.requestInterceptor(new AuthInterceptor(config.name(), config.auth(), secrets, tokens));
        }
        RestClient restClient = builder.build();
        return new EndpointRuntime(config, restClient, httpClient, circuitBreaker(config));
    }

    /** When this node's circuit for {@code name} last changed state, or was built; for the circuit API (US-5.3). */
    public java.time.Instant circuitStateSince(String name) {
        return circuitSince.get(name);
    }

    /**
     * US-5.3: count-based, from the endpoint's settings (the global defaults fill what it omits), opening to
     * half-open by itself. Every transition is logged at WARN with the failure rate.
     */
    private CircuitBreaker circuitBreaker(EndpointConfig config) {
        EndpointConfig.CircuitBreaker c = config.circuitBreaker();
        CircuitBreakerConfig.Builder b = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .automaticTransitionFromOpenToHalfOpenEnabled(true);
        if (c != null) {
            b.slidingWindowSize(c.slidingWindowSize()).minimumNumberOfCalls(c.minimumCalls())
                    .failureRateThreshold(c.failureRateThreshold())
                    .slowCallDurationThreshold(Duration.ofMillis(c.slowCallDurationMs()))
                    .slowCallRateThreshold(c.slowCallRateThreshold())
                    .waitDurationInOpenState(Duration.ofMillis(c.waitInOpenMs()))
                    .permittedNumberOfCallsInHalfOpenState(c.halfOpenCalls());
        }
        CircuitBreaker cb = CircuitBreaker.of(config.name(), b.build());
        circuitSince.put(config.name(), java.time.Instant.now(clock));
        cb.getEventPublisher().onStateTransition(e -> {
            circuitSince.put(config.name(), java.time.Instant.now(clock));
            log.warn("circuit state changed endpoint={} from={} to={} failureRate={}", config.name(),
                    e.getStateTransition().getFromState(), e.getStateTransition().getToState(),
                    cb.getMetrics().getFailureRate());
        });
        return cb;
    }
}
