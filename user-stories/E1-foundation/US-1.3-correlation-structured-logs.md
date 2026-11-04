# US-1.3 Correlation IDs and structured JSON logs
**Epic:** E1 Foundation · **Priority:** Must · **Size:** S · **Depends on:** US-1.1 · **Release:** R1

## User story
As an **Operations Engineer**, I want every log line in JSON with a correlation ID and run and
step context, and the correlation ID carried to every downstream API, so that I can follow one
business transaction across apiorch and all the systems it calls.

## Description
This story sets up the MDC-based logging context and the JSON log format used by every later
story:
- an inbound `X-Correlation-Id` is accepted, or generated when absent;
- the ID is echoed on responses and propagated to downstream calls and caller webhooks;
- MDC is propagated across the `step-io` thread pool and scheduled tasks.

**Out of scope:** what activity events are logged (US-7.1/7.2), masking (US-7.3), and
metrics and tracing export (US-7.5).

## Acceptance criteria
- [ ] **AC1:** Given an inbound request with `X-Correlation-Id: abc-123`, when it is processed,
  then the response carries `X-Correlation-Id: abc-123`, and every log line written while
  handling it has `"correlationId":"abc-123"`.
- [ ] **AC2:** Given an inbound request without the header, or with an invalid value (over 128
  characters or outside `[A-Za-z0-9._:-]`), when it is processed, then a UUID v4 is generated
  and used, and an invalid value is logged at DEBUG and replaced.
- [ ] **AC3:** Given a run executing steps on the `step-io` pool, when a step logs, then the
  line has `runId`, `pipeline`, `version`, `stepId`, `attempt`, `correlationId`, `traceId` and
  `spanId`, even though it runs on a different thread from the inbound request.
- [ ] **AC4:** Given the `prod` profile, when the app logs, then each line is a single-line JSON
  object (logstash encoder) with `@timestamp`, `level`, `logger`, `thread`, `message`, the MDC
  fields and `stack_trace` for exceptions. Given the `local` profile, logs use a human-readable
  pattern that includes `runId` and `stepId`.
- [ ] **AC5:** Given any outbound HTTP call made by apiorch (step, compensation, caller
  webhook), when it is sent, then it carries the run's `X-Correlation-Id`.
- [ ] **AC6:** Given a problem+json error response, including one produced during an MVC async
  dispatch such as a sync run that times out into `202` or fails, then its `correlationId`
  property equals the request's correlation ID.

## Technical details
**Components** (`com.crisil.apiorch.observability`)
- `CorrelationIdFilter` is a `OncePerRequestFilter` ordered before security. It reads, validates
  or generates the ID, stores it as request attribute `apiorch.correlationId`, puts it into MDC,
  sets the response header, and clears MDC in `finally`. It overrides
  `shouldNotFilterAsyncDispatch()` to return `false`. The async dispatch of a sync run response
  (US-4.1) then restores MDC from the request attribute, so the "run responded" log line and any
  problem response still carry the ID.
- `MdcSupport` offers `withRun(runCtx)` and `withStep(stepId, attempt)`, which return
  try-with-resources scopes that set and restore MDC keys.
- `MdcTaskDecorator` implements `TaskDecorator`. It copies the MDC map onto the `step-io`
  executor (US-4.3) and the scheduler threads, and restores the previous map afterwards.
- `OutboundHeaders.correlation(runCtx)` is used by `ResilientHttpClient` (US-4.3) and
  `OutboxDispatcher` (US-6.5).

**MDC keys** (fixed names, used in every story): `correlationId`, `runId`, `pipeline`,
`version`, `stepId`, `attempt`, `traceId`, `spanId`. Micrometer Tracing fills `traceId` and
`spanId`.

**logback-spring.xml**
- `<springProfile name="prod">`: `ConsoleAppender` with
  `net.logstash.logback.encoder.LogstashEncoder`. It includes the MDC keys above,
  `<shortenedLoggerNameLength>36`, and `customFields {"service":"apiorch-service"}`.
- `<springProfile name="local,test">`: the pattern
  `%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-}] [run=%X{runId:-} step=%X{stepId:-}#%X{attempt:-}] %logger{30} - %msg%n`.

**Dependency:** `net.logstash.logback:logstash-logback-encoder` **8.1**, the Jackson 2 line
(ADR-003). Decorators are configured with the 8.1 element `<jsonGeneratorDecorator class="…">`
(in code, `encoder.setJsonGeneratorDecorator(…)`, and a programmatic decorator must be
`start()`ed). In XML, a literal comma or closing brace in a mask `<value>` regex is written as
`\x2C` and `\x7D`, because a bare `,` or `}` cuts the regex and fails startup (ADR-003 F11).
US-7.3 owns the masking decorator.

**Logging rule (README §13):** never pass a sensitive value to a message placeholder. A value
rendered through `{}` is written into the free-text `message` field in clear, even when the same
value is masked as a structured field (ADR-001 F5, still valid per ADR-003; observed in the probe as `"message":"login
password=s3cret"` next to `"password":"***"`). Sensitive values go in as `StructuredArguments`
**without** a `{}`, which logs them as fields that masking reaches, or are not logged at all.

**Configuration:** `apiorch.correlation.header` (default `X-Correlation-Id`).

**Test notes**
- `CorrelationIdFilterTest` covers AC1/AC2.
- `MdcPropagationTest` submits a task to the decorated executor and asserts the MDC values (AC3).
- `JsonLogFormatTest` uses a ListAppender with LogstashEncoder and parses the JSON (AC4).
- `OutboundCorrelationIT` uses WireMock to verify the header on a step call (AC5). It runs once
  US-4.3 exists and can be written as a pending test until then.
