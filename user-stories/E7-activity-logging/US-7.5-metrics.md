# US-7.5 Operational metrics
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-7.1, US-5.3 · **Release:** R1

## User story
As an **Operations Engineer**, I want Prometheus metrics for run and step latency, outcomes,
payload sizes, resilience state and background jobs from the first release, so that I can
alert on degradation and see which downstream system is causing trouble.

## Description
apiorch exposes Micrometer metrics on `/actuator/prometheus` (management port 9091, restricted
by US-1.2). Tags are **low-cardinality**: pipeline, step, endpoint, status, outcome and reason.
Run IDs are **never** used as tags. Each later story that introduces a metric (R2/R3) registers
it through the same `ApiorchMetrics` facade and adds it to the alert pack (US-8.4). Distributed
tracing is US-7.9.

**Out of scope:** dashboards, alert rules (US-8.4) and tracing (US-7.9).

## Acceptance criteria
- [ ] **AC1:** Given runs executing, when scraped, then `apiorch_run_duration_seconds{pipeline,status}`
  exposes histogram buckets (p50, p95 and p99 computable), and so does
  `apiorch_step_duration_seconds{pipeline,step,endpoint,outcome}`.
- [ ] **AC2:** Given attempts, then `apiorch_step_attempts_total{endpoint,outcome}` counts every
  attempt by outcome, including `CIRCUIT_OPEN` and `DEADLINE_EXCEEDED`.
  `apiorch_retry_scheduled_total{endpoint,reason}` counts retries.
  `apiorch_payload_request_bytes` and `apiorch_payload_response_bytes` (distribution summaries,
  tag `endpoint`) record sizes.
- [ ] **AC3:** Given the circuit breakers, then `resilience4j_circuitbreaker_state{name,state}`,
  `resilience4j_circuitbreaker_calls_seconds` and `resilience4j_circuitbreaker_failure_rate`
  are exposed per endpoint, and stay correct after an endpoint runtime rebuild, with no
  duplicate or stale series (US-2.1).
- [ ] **AC4:** Given R1 operations, then the following are exposed:
  - `apiorch_recovery_runs_recovered_total`, `apiorch_recovery_steps_unknown_total` and
    `apiorch_lease_lost_total`;
  - `apiorch_runs_by_status{status}` (gauge, refreshed every 30 s from the database) and
    `apiorch_runs_driven` (gauge per node);
  - `apiorch_definition_published_total{pipeline}`.
- [ ] **AC5:** Given metric names and tags, when a run, step or endpoint is new, then no series
  carries `runId`, `correlationId` or a URL. This is verified by a test that scans all
  registered meters for forbidden tag keys.
- [ ] **AC6:** Given the later stories, then their meters are registered with the same facade:
  - `apiorch.admission.rejected` and `apiorch.runs.inflight` (US-5.4);
  - `apiorch.callback.timeouts` (US-6.4);
  - `apiorch.outbox.*` (US-6.5);
  - `apiorch.compensation.*` (US-5.6);
  - `apiorch.retention.*` (US-7.8);
  - `apiorch.pipelines.disabled` (US-3.7);
  - `apiorch.runs.rejected` (US-4.8).

  Each one is listed in US-8.4.

## Technical details
**Dependencies:** `micrometer-registry-prometheus`, `resilience4j-micrometer`.

**Components** (`com.crisil.apiorch.observability`)
- `ApiorchMetrics` is the single facade over `MeterRegistry`. Timers use
  `publishPercentileHistogram()` with SLO boundaries of 50 ms, 100 ms, 250 ms, 500 ms, 1 s,
  2.5 s, 5 s and 10 s.
- `TaggedCircuitBreakerMetrics` is bound per endpoint `CircuitBreakerRegistry`. On rebuild,
  `EndpointClientRegistry` removes the old meters (`registry.remove(meter)`) before binding the
  new ones. Retry metrics come from apiorch's own counters (US-5.2); per-execution Retry
  instances are never bound.
- `RunStatusGauge` runs a `@Scheduled` task every 30 s:
  `SELECT status, count(*) FROM pipeline_run WHERE ended_at IS NULL GROUP BY status`.

**Configuration**
```yaml
management.metrics.distribution.percentiles-histogram.http.server.requests: true
management.metrics.tags.service: apiorch-service
```

**Test notes**
- `MetricsIT` runs a mixed scenario (success, retry, open circuit, timeout), scrapes
  `/actuator/prometheus`, and asserts series and tags, including after an endpoint rebuild
  (AC1–AC4).
- `MetricCardinalityTest` covers AC5.
