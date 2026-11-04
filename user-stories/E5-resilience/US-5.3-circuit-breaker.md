# US-5.3 Circuit breaker per downstream endpoint
**Epic:** E5 Resilience · **Priority:** Must · **Size:** M · **Depends on:** US-2.1, US-5.2 · **Release:** R1

## User story
As an **Operations Engineer**, I want apiorch to stop calling a downstream system that is
clearly failing, and probe it again after a cool-down, so that a broken system on another
infrastructure isn't flooded with retries and runs fail fast instead of waiting on timeouts.

## Description
Each endpoint has one Resilience4j `CircuitBreaker` **per node**, configured from the endpoint's
`resilience.circuitBreaker` settings, or the global defaults when those are absent. It sits
**inside** the retry from US-5.2, in the order `Retry → CircuitBreaker → Bulkhead → HTTP`, so the
breaker records every attempt. Once it opens, the next attempt is rejected with `CIRCUIT_OPEN`.
The retry never retries that outcome, so the step stops retrying as soon as the circuit opens.
- **Failures counted:** `CONNECT_ERROR`, `IO_ERROR`, `TIMEOUT`, 5xx and 429. Other 4xx are the
  caller's problem and don't count.
- **Slow calls** above `slowCallDurationMs` count towards the slow-call rate.
- **Open circuit:** calls are rejected immediately with outcome `CIRCUIT_OPEN`, which is not
  retried, and the step's `onFailure` policy applies.
- **Recovery:** after `waitInOpenMs` the circuit becomes half-open and lets `halfOpenCalls`
  probes through.

Operators can view a circuit's state and reset it.

**Out of scope:** circuit state shared across nodes. Each node protects itself, which is the
standard Resilience4j model.

## Acceptance criteria
- [ ] **AC1:** Given endpoint `score-svc` with `slidingWindowSize: 10`, `minimumCalls: 5` and
  `failureRateThreshold: 50`, when 5 consecutive calls fail with 503, then the circuit opens. The
  next step call to `score-svc` doesn't reach WireMock; its attempt is recorded with outcome
  `CIRCUIT_OPEN` and `duration_ms = 0`, and the event `CIRCUIT_OPEN_REJECTED` `{endpoint}` is
  recorded.
- [ ] **AC2:** Given an open circuit and a step with `onFailure: CONTINUE`, when it is rejected,
  then the step is `FAILED` with code `CIRCUIT_OPEN` and the run ends `COMPLETED_WITH_ERRORS`.
  With `FAIL_RUN`, the run ends `FAILED`. Either way, the rejected attempt sends no request and
  doesn't wait for the step timeout: the step completes in under 1 s against a stub whose delay
  is 10 s.
- [ ] **AC3:** Given `waitInOpenMs: 2000`, when 2 s have passed and WireMock is healthy again,
  then up to `halfOpenCalls` (3) probe calls pass. If they succeed the circuit closes, and if
  they fail it reopens.
- [ ] **AC4:** Given responses `400`/`404`/`422` from the endpoint, when counted, then they
  don't increase the failure rate, and 20 consecutive 404s leave the circuit `CLOSED`.
- [ ] **AC5:** Given a circuit state change (`CLOSED→OPEN`, `OPEN→HALF_OPEN`, `HALF_OPEN→CLOSED`
  or `HALF_OPEN→OPEN`), then a WARN log `circuit state changed endpoint=score-svc
  from=CLOSED to=OPEN failureRate=60.0` is written. The state metric is exposed by US-7.5.
- [ ] **AC6:** Given an operator (`apiorch.ops`), when they call
  `GET /api/v1/admin/endpoints/score-svc/circuit`, then they receive this node's state and
  counters. `POST …/circuit/reset` with `{"reason":"KYC back after INC-5012"}` closes the
  circuit on this node immediately. With `?allNodes=true`, the endpoint runtime is rebuilt on
  every node within `apiorch.endpoints.refresh-interval-ms`. A reset without `reason` gets `400`.
  Every reset is logged at WARN with actor and reason, and stored as an `endpoint_audit` row
  `CIRCUIT_RESET` once US-2.3 ships.
- [ ] **AC7:** Given the endpoint's circuit-breaker settings are updated (US-2.1), when the
  runtime is rebuilt, then the new thresholds apply and the circuit starts `CLOSED`.

## Technical details
**Components**
- `http.EndpointClientRegistry.EndpointRuntime` holds a `CircuitBreaker` created from a
  per-endpoint `CircuitBreakerRegistry`. Its configuration:
  - `slidingWindowType(COUNT_BASED)`;
  - `slidingWindowSize`, `minimumNumberOfCalls`, `failureRateThreshold`,
    `slowCallDurationThreshold`, `slowCallRateThreshold`;
  - `waitDurationInOpenState`, `permittedNumberOfCallsInHalfOpenState`;
  - `automaticTransitionFromOpenToHalfOpenEnabled(true)`;
  - `recordResult(AttemptResult::countsAsFailure)`;
  - `ignoreExceptions(...)` for non-counted errors.
- `ResilientHttpClient` composes the attempt supplier as
  `CircuitBreaker.decorateCompletionStage(cb, Bulkhead.decorateCompletionStage(bh, httpCall))`,
  and the US-5.2 `Retry` wraps that supplier. Each attempt is one circuit-breaker call, which is
  the standard Resilience4j order (Retry outermost, Bulkhead innermost).
- `CallNotPermittedException` maps to attempt outcome `CIRCUIT_OPEN`, a `step_attempt` row with
  `method` and `url` but no request sent, and `circuit_state = OPEN`.
- The `circuit_state` column is set on every attempt row from `circuitBreaker.getState()`.
- `endpoint.CircuitController`:
  - `GET` returns `{endpoint, nodeId, state, failureRate, slowCallRate, bufferedCalls,
    failedCalls, slowCalls, notPermittedCalls, stateSince}`.
  - `POST …/reset` calls `circuitBreaker.reset()`.
  - `?allNodes=true` updates `endpoint.updated_at` and `row_version` (no config change), so the
    US-2.1 refresh rebuilds the runtime everywhere.

**API contract**
- `GET /api/v1/admin/endpoints/{name}/circuit` returns `200 {…}`.
- `POST /api/v1/admin/endpoints/{name}/circuit/reset?allNodes=false` takes `{ "reason": "1–500
  chars" }` and returns `200` with the state after the reset.

**Configuration:** `apiorch.resilience.defaults.circuit-breaker.*` (README §11) and endpoint
`resilience.circuitBreaker`.

**Logging and metrics:** state transitions go through
`circuitBreaker.getEventPublisher().onStateTransition(...)` to a WARN log.
`TaggedCircuitBreakerMetrics` is bound per endpoint by US-7.5. When a runtime is rebuilt, the
old meters are removed first.

**Test notes**
- `CircuitBreakerIT` covers opening on 5×503, the rejection timing, the half-open probe that
  closes the circuit, and the half-open failure that reopens it (AC1–AC3), using small windows
  and `waitInOpenMs: 500`.
- `CircuitIgnores4xxIT` covers AC4.
- `CircuitEventsIT` captures logs and checks the metric gauge (AC5).
- `CircuitApiIT` covers GET, reset and allNodes across two contexts (AC6).
- `CircuitConfigReloadIT` covers AC7.
