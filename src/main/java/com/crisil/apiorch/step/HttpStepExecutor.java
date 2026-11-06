package com.crisil.apiorch.step;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;
import com.crisil.apiorch.http.EndpointClientRegistry;
import com.crisil.apiorch.http.EndpointRuntime;
import com.crisil.apiorch.http.ExchangeRecorder;
import com.crisil.apiorch.http.auth.AuthFailureException;
import com.crisil.apiorch.http.auth.AuthInterceptor;
import com.crisil.apiorch.observability.AttemptRecord;
import com.crisil.apiorch.observability.OutboundHeaders;

/**
 * Runs a SYNC step as one HTTP call (US-4.3): builds the request from the context snapshot, records
 * {@code REQUEST_SENT}, sends it through the endpoint's {@code RestClient} (recorder outermost, then auth), reads the
 * whole response through the counting tee, parses it, and applies {@code successWhen} on top of 2xx. It returns the
 * outcome with the attempt for the coordinator to store; it never writes the step's state itself. Retries, timeouts
 * per attempt and the circuit breaker wrap this call in E5. Called on {@code step-io}, outside any transaction.
 */
@Component
public class HttpStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpStepExecutor.class);
    static final int MAX_ERROR_MESSAGE = 1000;
    /** An attempt with this little budget left is not started (US-5.1). */
    static final long MIN_BUDGET_MS = 100;
    public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final EndpointClientRegistry endpoints;
    private final ExpressionEvaluator evaluator;
    private final AttemptLog attempts;
    private final OutboundHeaders outbound;
    private final Clock clock;

    public HttpStepExecutor(EndpointClientRegistry endpoints, ExpressionEvaluator evaluator, AttemptLog attempts,
            OutboundHeaders outbound, Clock clock) {
        this.endpoints = endpoints;
        this.evaluator = evaluator;
        this.attempts = attempts;
        this.outbound = outbound;
        this.clock = clock;
    }

    /** What one call produced: the exchange, a failure, or both. */
    private record Call(ExchangeRecorder.Exchange exchange, RuntimeException failure, List<String> refreshes,
            long elapsedMs) {
    }

    @Override
    public StepOutcome execute(StepInvocation invocation) {
        CompiledStep step = invocation.step();
        String endpointName = step.definition().endpoint();
        Optional<EndpointRuntime> runtime = endpoints.get(endpointName);
        if (runtime.isEmpty()) {
            return StepOutcome.failed(null, "ENDPOINT_NOT_FOUND", "Endpoint '" + endpointName + "' does not exist", 0, 0);
        }
        if (!runtime.get().config().enabled()) {
            return StepOutcome.failed(null, "ENDPOINT_DISABLED", "Endpoint '" + endpointName + "' is disabled", 0, 0);
        }
        RequestBuilder.StepRequest request;
        try {
            request = RequestBuilder.build(step, invocation.context(), evaluator, runtime.get().config().baseUrl(),
                    fixedHeaders(invocation));
        } catch (RequestBuilder.BuildException e) {
            return StepOutcome.failed(null, "EXPRESSION_ERROR", limit(e.getMessage()), 0, 0);
        }

        // US-5.1: the attempt timeout is the step's, capped by what is left of the segment
        long stepTimeout = invocation.timeoutMs() != null ? invocation.timeoutMs()
                : runtime.get().config().readTimeoutMs();
        long attemptTimeout = stepTimeout;
        boolean cappedByBudget = false;
        if (invocation.deadline() != null) {
            long budget = java.time.Duration.between(Instant.now(clock), invocation.deadline()).toMillis();
            if (budget <= MIN_BUDGET_MS) {
                return StepOutcome.failed(null, DEADLINE_EXCEEDED, "Run deadline reached before the request was sent",
                        0, 0);
            }
            if (budget < stepTimeout) {
                attemptTimeout = budget;
                cappedByBudget = true;
            }
        }

        int attemptNo = invocation.attemptNo();
        CircuitBreaker circuit = runtime.get().circuitBreaker();
        String circuitState = circuit.getState().name();
        if (!circuit.tryAcquirePermission()) {
            return rejected(invocation, endpointName, request, attemptNo);
        }
        long seq;
        try {
            seq = attempts.requestSent(invocation, attemptNo, request.method());
        } catch (RuntimeException e) {
            circuit.releasePermission();
            throw e;
        }
        Instant startedAt = Instant.now(clock);
        log.info("step started method={} url={}", request.method(), masked(request.uri()));
        Call call = send(runtime.get().restClient(attemptTimeout), request);
        ExchangeRecorder.Exchange exchange = call.exchange();
        // a failed exchange never completed, so its own duration is not the attempt's (US-5.1 AC1)
        long durationMs = exchange != null && call.failure() == null ? exchange.durationMs() : call.elapsedMs();

        String outcome;
        String errorMessage = null;
        String errorClass = null;
        StepOutcome result;
        if (call.failure() != null) {
            Throwable cause = rootIo(call.failure());
            outcome = classify(cause);
            if (outcome.equals("TIMEOUT")) {
                if (cappedByBudget) {
                    outcome = DEADLINE_EXCEEDED;
                }
                log.warn("attempt timed out timeoutMs={} cappedByBudget={}", attemptTimeout, cappedByBudget);
            }
            errorClass = cause.getClass().getSimpleName();
            errorMessage = limit(cause instanceof AuthFailureException auth
                    ? auth.code() + ": " + auth.getMessage() : String.valueOf(cause.getMessage()));
            result = StepOutcome.failed(null, outcome, errorMessage, 1, durationMs);
        } else if (exchange.responseTooLarge()) {
            outcome = "RESPONSE_TOO_LARGE";
            errorMessage = "Response is larger than apiorch.run.max-response-bytes";
            result = StepOutcome.failed(exchange.status(), outcome, errorMessage, 1, durationMs);
        } else {
            ObjectNode headers = ResponseParser.headers(exchange.responseHeaders());
            JsonNode body = ResponseParser.body(header(exchange.responseHeaders(), "Content-Type"),
                    exchange.responseBody());
            int status = exchange.status();
            if (status < 200 || status > 299) {
                outcome = "HTTP_ERROR";
                errorMessage = "HTTP " + status;
                result = new StepOutcome(StepOutcome.Status.FAILED, status, headers, body, 1, durationMs, outcome,
                        errorMessage, List.of());
            } else {
                String check = successCheck(step, invocation.context(), status, headers, body);
                if (check == null) {
                    outcome = "SUCCESS";
                    result = StepOutcome.succeeded(status, headers, body, 1, durationMs);
                } else {
                    outcome = check.startsWith("EXPRESSION_ERROR") ? "EXPRESSION_ERROR" : "SUCCESS_CHECK_FAILED";
                    errorMessage = limit(check.substring(check.indexOf(':') + 1).strip());
                    result = new StepOutcome(StepOutcome.Status.FAILED, status, headers, body, 1, durationMs, outcome,
                            errorMessage, List.of());
                }
            }
        }
        report(circuit, outcome, result.httpStatus(), durationMs);
        log.info("step finished status={} httpStatus={} durationMs={} requestBytes={} responseBytes={}",
                result.status(), result.httpStatus(), durationMs,
                exchange != null ? exchange.requestBytes() : 0, exchange != null ? exchange.responseBytes() : 0);

        AttemptRecord record = new AttemptRecord(invocation.runId(), step.id(), invocation.execNo(), attemptNo, "REQUEST",
                seq, endpointName, request.method(), exchange != null ? exchange.url() : request.uri().toString(),
                exchange != null ? exchange.requestHeaders() : request.headers(),
                exchange != null ? exchange.requestBody() : null,
                exchange != null ? exchange.requestBytes() : request.bodyBytes() == null ? 0 : request.bodyBytes().length,
                exchange != null ? exchange.status() : null,
                exchange != null ? exchange.responseHeaders() : Map.of(),
                exchange != null ? exchange.responseBody() : null,
                exchange != null ? exchange.responseBytes() : 0,
                outcome, errorClass, errorMessage, circuitState, null, startedAt, startedAt.plusMillis(durationMs),
                durationMs);
        boolean responded = exchange != null && exchange.status() != null;
        return result.withTrail(List.of(new StepAttempt(record, responded, call.refreshes())));
    }

    /**
     * US-5.3: the circuit is open, so nothing is sent and nothing waits. The attempt is still recorded (method, URL,
     * {@code duration_ms = 0}, {@code circuit_state = OPEN}); the coordinator gives it the {@code seq} of
     * {@code CIRCUIT_OPEN_REJECTED}. {@code CIRCUIT_OPEN} is never retried, so the step's policy applies at once.
     */
    private StepOutcome rejected(StepInvocation invocation, String endpointName, RequestBuilder.StepRequest request,
            int attemptNo) {
        Instant now = Instant.now(clock);
        String message = "Circuit open for endpoint '" + endpointName + "'";
        log.warn("attempt rejected endpoint={} outcome={}", endpointName, CIRCUIT_OPEN);
        AttemptRecord record = new AttemptRecord(invocation.runId(), invocation.step().id(), invocation.execNo(),
                attemptNo, "REQUEST", 0, endpointName, request.method(), request.uri().toString(), request.headers(),
                null, 0, null, Map.of(), null, 0, CIRCUIT_OPEN, null, message, "OPEN", null, now, now, 0);
        return StepOutcome.failed(null, CIRCUIT_OPEN, message, 1, 0)
                .withTrail(List.of(new StepAttempt(record, false, List.of())));
    }

    /**
     * What this attempt tells the circuit: connect, IO and timeout failures, 5xx and 429 count as failures; a
     * deadline cut or an auth failure says nothing about the endpoint and is not counted; anything else, other 4xx
     * included, is a success (slow ones count towards the slow-call rate).
     */
    static void report(CircuitBreaker circuit, String outcome, Integer httpStatus, long durationMs) {
        boolean failure = switch (outcome) {
            case "CONNECT_ERROR", "IO_ERROR", "TIMEOUT" -> true;
            case "HTTP_ERROR" -> httpStatus != null && (httpStatus >= 500 || httpStatus == 429);
            default -> false;
        };
        if (failure) {
            circuit.onError(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new IllegalStateException(outcome + (httpStatus != null ? " " + httpStatus : "")));
        } else if (outcome.equals(DEADLINE_EXCEEDED) || outcome.equals("AUTH_ERROR")) {
            circuit.releasePermission();
        } else {
            circuit.onSuccess(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private Map<String, String> fixedHeaders(StepInvocation invocation) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (invocation.correlationId() != null) {
            headers.putAll(outbound.correlation(invocation.correlationId()));
        }
        headers.put("X-Apiorch-Run-Id", invocation.runId().toString());
        headers.put("X-Apiorch-Step-Id", invocation.step().id());
        headers.put("Idempotency-Key", invocation.runId() + ":" + invocation.step().id() + ":" + invocation.execNo());
        headers.put("Accept", "application/json");
        return headers;
    }

    private static Call send(RestClient client, RequestBuilder.StepRequest request) {
        List<String> refreshes = new ArrayList<>();
        RuntimeException[] failure = new RuntimeException[1];
        ExchangeRecorder.Exchange[] partial = new ExchangeRecorder.Exchange[1];
        long start = System.nanoTime();
        ExchangeRecorder.Recorded<Integer> recorded = ExchangeRecorder.record(() -> {
            try {
                return AuthInterceptor.withListener(refreshes::add, () -> {
                    RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(request.method()))
                            .uri(request.uri())
                            .headers(h -> request.headers().forEach(h::set));
                    if (request.bodyBytes() != null) {
                        spec.body(request.bodyBytes());
                    }
                    return spec.exchange((req, res) -> {
                        drain(res.getBody());
                        return res.getStatusCode().value();
                    });
                });
            } catch (RuntimeException e) {
                failure[0] = e;
                partial[0] = ExchangeRecorder.current();
                return null;
            }
        });
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        // on failure keep what was captured so far: a response may have arrived before reading its body failed
        ExchangeRecorder.Exchange exchange = failure[0] != null ? partial[0] : recorded.exchange();
        return new Call(exchange, failure[0], refreshes, elapsed);
    }

    private static void drain(InputStream body) throws IOException {
        byte[] buf = new byte[8192];
        while (body.read(buf) >= 0) {
            // the tee keeps what is needed and counts the rest
        }
    }

    /** {@code null} when the step passes, else {@code "<CODE>: <message>"}. */
    private String successCheck(CompiledStep step, JsonNode context, int status, ObjectNode headers, JsonNode body) {
        if (step.successWhen() == null) {
            return null;
        }
        ObjectNode scope = context.deepCopy();
        scope.put("status", status);
        scope.set("headers", headers);
        scope.set("body", body);
        try {
            JsonNode verdict = evaluator.evaluate(step.successWhen(), scope);
            return verdict.isBoolean() && verdict.booleanValue() ? null
                    : "SUCCESS_CHECK_FAILED: successWhen was " + (verdict.isMissingNode() ? "undefined" : verdict);
        } catch (ExpressionException e) {
            return "EXPRESSION_ERROR: successWhen: " + e.getMessage() + (e.code() != null ? " [" + e.code() + "]" : "");
        }
    }

    /** The cause that names the failure: an auth, timeout or connect exception if any, else the deepest. */
    private static Throwable rootIo(Throwable failure) {
        Throwable deepest = failure;
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof AuthFailureException || c instanceof HttpTimeoutException || c instanceof ConnectException
                    || c instanceof SocketTimeoutException) {
                return c;
            }
            deepest = c;
        }
        return deepest;
    }

    static String classify(Throwable cause) {
        if (cause instanceof AuthFailureException) {
            return "AUTH_ERROR";
        }
        if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
            return "CONNECT_ERROR";
        }
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            return "TIMEOUT";
        }
        return "IO_ERROR";
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** The URL without its query, which may carry data. */
    private static String masked(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getRawPath();
    }

    static String limit(String message) {
        return message == null || message.length() <= MAX_ERROR_MESSAGE ? message
                : message.substring(0, MAX_ERROR_MESSAGE);
    }
}
