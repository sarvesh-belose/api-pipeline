# US-7.9 Distributed tracing
**Epic:** E7 Activity logging & data protection · **Priority:** Should · **Size:** M · **Depends on:** US-7.5 · **Release:** R3

## User story
As an **Operations Engineer**, I want distributed traces that link the caller's request,
apiorch's run and step spans, and every downstream call, so that I can see where time is spent
across systems and hand a trace id to another team.

## Description
apiorch exports OpenTelemetry traces over OTLP. Each segment of a run is a span, each step
attempt is a child span, and the HTTP client span propagates W3C `traceparent` to downstream
APIs. A run resumed later by a callback, possibly on another node, starts a **new trace** that
is linked to the original one. Sampling never affects metrics (US-7.5).

**Out of scope:** trace storage and the UI (the ops team's tracing backend).

## Acceptance criteria
- [ ] **AC1:** Given an inbound request with `traceparent`, when the run calls downstream APIs,
  then each outbound request carries `traceparent` with the same trace id. The spans
  `apiorch.run` → `apiorch.step` (attributes `run.id`, `step.id`, `exec.no`, `endpoint`,
  `attempt`) → `http client` are exported to the OTLP endpoint.
- [ ] **AC2:** Given log lines during the run, then `traceId` and `spanId` in MDC (US-1.3) match
  the exported spans.
- [ ] **AC3:** Given an async run woken by a callback on another node (US-6.3), when traced, then
  the resumed segment is a new trace with a **span link** to the run's first segment, and both
  carry `run.id`.
- [ ] **AC4:** Given `management.tracing.sampling.probability=0.1`, when runs execute, then
  about 10% are sampled, and the metric counts match an unsampled run of the same load.
- [ ] **AC5:** Given the OTLP endpoint is unreachable, when spans are exported, then runs are
  unaffected: no latency increase above 5 ms p95 per step. Export errors are logged at WARN at
  most once per minute.

## Technical details
**Dependencies:** `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`.

**Components** (`com.crisil.apiorch.observability`)
- `RunObservation` and `StepObservation` use the Micrometer Observation API, around coordinator
  segments and step attempts.
- Endpoint `RestClient`s are built from the Boot-managed `RestClient.Builder` with `clone()`
  (US-2.1), so the client observation and `traceparent` injection are automatic.
- The context is propagated across `step-io` with `ContextPropagatingTaskDecorator`, combined
  with `MdcTaskDecorator` (US-1.3).
- Span links: `RUN_STARTED.details` stores `{traceId, spanId}` (US-7.1). A later segment reads
  them and adds a link.

**Configuration**
```yaml
management:
  tracing.sampling.probability: ${APIORCH_TRACE_SAMPLING:1.0}
  otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

**Test notes**
- `TracePropagationIT` uses an in-memory OTel `SpanExporter` and WireMock (AC1, AC2).
- `ResumeSpanLinkIT` covers AC3.
- `SamplingConfigTest` covers AC4.
- `ExporterDownIT` covers AC5 and is tagged `perf`.
