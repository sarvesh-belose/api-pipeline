package com.crisil.apiorch.observability;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * The single facade over the {@link MeterRegistry} (US-7.5 core). Every apiorch meter is registered here, with
 * low-cardinality tags only: pipeline, step, endpoint, status, outcome and reason. A tag key that could carry a
 * per-run value ({@code runId}, {@code correlationId}, {@code url}, …) is refused (AC5). Timers publish a percentile
 * histogram with the SLO boundaries of README §14. Later stories add their meters here.
 */
@Component
public class ApiorchMetrics {

    /** Normalised (lower case, no {@code _ - .}). Spring's {@code uri} tag is the route template, so it is allowed. */
    static final Set<String> FORBIDDEN_TAG_KEYS = Set.of("runid", "run", "correlationid", "url", "traceid", "spanid",
            "idempotencykey", "principal", "caller");

    private static final Duration[] SLOS = {Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5),
            Duration.ofSeconds(10)};

    private final MeterRegistry registry;

    private final java.util.concurrent.atomic.AtomicInteger driven = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> byStatus =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ApiorchMetrics(MeterRegistry registry) {
        this.registry = registry;
        io.micrometer.core.instrument.Gauge.builder("apiorch.runs.driven", driven,
                java.util.concurrent.atomic.AtomicInteger::get).description("Runs this node holds a lease on")
                .register(registry);
    }

    /** {@code apiorch.run.duration{pipeline,status}}. */
    public void runFinished(String pipeline, String status, Duration duration) {
        timer("apiorch.run.duration", "Run duration from start to its final state", Tags.of("pipeline", pipeline,
                "status", status)).record(duration);
    }

    /** {@code apiorch.step.duration{pipeline,step,endpoint,outcome}}. */
    public void stepFinished(String pipeline, String step, String endpoint, String outcome, Duration duration) {
        timer("apiorch.step.duration", "Step duration including every attempt", Tags.of("pipeline", pipeline,
                "step", step, "endpoint", endpoint, "outcome", outcome)).record(duration);
    }

    /** {@code apiorch.step.attempts{endpoint,outcome}} and the payload size summaries (tag {@code endpoint}). */
    public void attempt(String endpoint, String outcome, long requestBytes, long responseBytes) {
        counter("apiorch.step.attempts", "Downstream call attempts by outcome",
                Tags.of("endpoint", endpoint, "outcome", outcome)).increment();
        summary("apiorch.payload.request.bytes", "Request body size as sent", Tags.of("endpoint", endpoint))
                .record(requestBytes);
        summary("apiorch.payload.response.bytes", "Response body size as received", Tags.of("endpoint", endpoint))
                .record(responseBytes);
    }

    /** {@code apiorch.retry.scheduled{endpoint,reason}} (US-5.2). */
    public void retryScheduled(String endpoint, String reason) {
        counter("apiorch.retry.scheduled", "Retries scheduled", Tags.of("endpoint", endpoint, "reason", reason))
                .increment();
    }

    /** {@code apiorch.definition.published{pipeline}}. */
    public void definitionPublished(String pipeline) {
        counter("apiorch.definition.published", "Versions published or rolled back", Tags.of("pipeline", pipeline))
                .increment();
    }

    /** {@code apiorch.payload.access.denied}: a detail read refused with {@code 403} (US-7.4 AC9). */
    public void payloadAccessDenied() {
        counter("apiorch.payload.access.denied", "Payload detail reads refused", Tags.empty()).increment();
    }

    /** {@code apiorch.recovery.runs.recovered} (US-5.5). */
    public void runRecovered() {
        counter("apiorch.recovery.runs.recovered", "Runs claimed and continued by recovery", Tags.empty()).increment();
    }

    /** {@code apiorch.recovery.steps.unknown}: steps whose outcome was lost with their node (US-5.5). */
    public void stepUnknown() {
        counter("apiorch.recovery.steps.unknown", "Steps found running by recovery and not safe to repeat",
                Tags.empty()).increment();
    }

    /** {@code apiorch.runs.driven}: runs this node holds a lease on, set at each renewal (US-5.5). */
    public void runsDriven(int count) {
        driven.set(count);
    }

    /** {@code apiorch.runs.by.status{status}}: unfinished runs per status, from the database (US-7.5 AC4). */
    public void runsByStatus(java.util.Map<String, Long> counts) {
        for (com.crisil.apiorch.engine.RunStatus status : com.crisil.apiorch.engine.RunStatus.values()) {
            if (!status.terminal()) {
                byStatus.computeIfAbsent(status.name(), s -> {
                    java.util.concurrent.atomic.AtomicLong value = new java.util.concurrent.atomic.AtomicLong();
                    io.micrometer.core.instrument.Gauge.builder("apiorch.runs.by.status", value,
                            java.util.concurrent.atomic.AtomicLong::get).description("Unfinished runs by status")
                            .tags(checked(Tags.of("status", s))).register(registry);
                    return value;
                }).set(counts.getOrDefault(status.name(), 0L));
            }
        }
    }

    /** {@code apiorch.admission.rejected}: new runs refused at this node's limit (US-5.4). */
    public void admissionRejected() {
        counter("apiorch.admission.rejected", "New runs refused by admission control", Tags.empty()).increment();
    }

    /** {@code apiorch.runs.inflight}: the admission count of runs this node drives (US-5.4). */
    public void runsInflight(java.util.concurrent.atomic.AtomicInteger driven) {
        io.micrometer.core.instrument.Gauge.builder("apiorch.runs.inflight", driven,
                java.util.concurrent.atomic.AtomicInteger::get).description("Runs this node drives (admission count)")
                .register(registry);
    }

    /** {@code apiorch.lease.lost}. */
    public void leaseLost() {
        counter("apiorch.lease.lost", "Work discarded because this node no longer owned the run", Tags.empty())
                .increment();
    }

    public Counter counter(String name, String description, Tags tags) {
        return Counter.builder(name).description(description).tags(checked(tags)).register(registry);
    }

    public Timer timer(String name, String description, Tags tags) {
        return Timer.builder(name).description(description).tags(checked(tags)).publishPercentileHistogram()
                .serviceLevelObjectives(SLOS).register(registry);
    }

    public DistributionSummary summary(String name, String description, Tags tags) {
        return DistributionSummary.builder(name).description(description).baseUnit("bytes").tags(checked(tags))
                .register(registry);
    }

    static Tags checked(Tags tags) {
        tags.forEach(tag -> {
            String key = tag.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
            if (FORBIDDEN_TAG_KEYS.contains(key)) {
                throw new IllegalArgumentException("Tag '" + tag.getKey() + "' would make a series per run");
            }
        });
        return tags;
    }
}
