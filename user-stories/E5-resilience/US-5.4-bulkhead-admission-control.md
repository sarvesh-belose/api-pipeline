# US-5.4 Bulkhead per endpoint and inbound admission control
**Epic:** E5 Resilience · **Priority:** Must · **Size:** S · **Depends on:** US-5.3, US-5.5 · **Release:** R1

## User story
As an **Operations Engineer**, I want to cap concurrent calls to each downstream system and the
number of runs each node drives, so that one slow system can't take every worker thread and a
traffic spike is shed cleanly instead of overloading apiorch and every API behind it.

## Description
- **Bulkhead.** Each endpoint has a Resilience4j **semaphore bulkhead** (`maxConcurrentCalls`,
  `maxWaitMs`, default **0**), applied innermost: `Retry → CircuitBreaker → Bulkhead → HTTP`.
  - A permit is held only while a request is in flight.
  - With `maxWaitMs = 0`, a full bulkhead rejects at once with outcome `BULKHEAD_FULL`, so no
    thread blocks waiting for a permit.
  - Nothing was sent, so retry backoff (US-5.2) handles the wait.
- **Admission control.** Each node limits the number of runs it **currently drives**, meaning
  runs it holds a lease on, whatever their status (README §5.1). The limit is
  `apiorch.admission.max-in-flight-runs`.
  - The count is maintained by `RunLeaseService` hooks, so it can't drift: +1 on claim or
    create, −1 on release or lease loss.
  - Only **new** runs are gated. Recovered and woken runs are existing work: they are counted,
    but never rejected.
  - Replays (US-4.7) are looked up before the gate.
- **Executor saturation.** If the `step-io` queue rejects a task, the attempt gets outcome
  `BULKHEAD_FULL`.

**Why this is in R1.** Without it, R1 ships sync and caller-async runs with retries and circuit
breakers but **no backpressure**: no per-endpoint cap, no admission gate and no handling of a
saturated executor. One slow downstream then consumes the whole fixed `step-io` pool and every
pipeline degrades together — the precise failure this product exists to prevent, and the one a
retry storm makes worse rather than better. A circuit breaker only reacts after calls have already
failed; a bulkhead stops one endpoint from taking the shared resource in the first place. It is an
**S**, it is the cheapest protection in the backlog, and it was the last story scheduled.

**Out of scope:** per-pipeline or per-caller quotas and rate limiting.

## Acceptance criteria
- [ ] **AC1:** Given endpoint `score-svc` with `maxConcurrentCalls: 5` (`maxWaitMs: 0`) and
  WireMock delaying 1 s, when 20 runs call it at once, then at most 5 requests are in flight at
  `score-svc` at any time. Rejected calls record an attempt with outcome `BULKHEAD_FULL` and the
  event `BULKHEAD_REJECTED`, and are retried with backoff. All 20 runs eventually succeed when
  `maxAttempts` and the budget allow it.
- [ ] **AC2:** Given `score-svc` is saturated, when runs also call `kyc-svc`, then the p95
  latency of `kyc-svc` steps stays within 100 ms of its unsaturated baseline, measured in the
  same test (isolation).
- [ ] **AC3:** Given `apiorch.admission.max-in-flight-runs=50` and this node drives 50 runs, when
  a new run is requested, then the response is `429` `admission-rejected` with `Retry-After: 1`,
  no run row is created, and `apiorch.admission.rejected` is incremented.
- [ ] **AC4:** Given runs in `WAITING_CALLBACK` or `COMPLETED_PENDING_ASYNC` that no node is
  driving, then they don't count. A `COMPLETED_PENDING_ASYNC` run that is executing
  post-completion dependents **does** count while its lease is held.
- [ ] **AC5:** Given this node recovers or wakes 10 runs while at its limit, when it claims them,
  then the claims succeed. The count may temporarily exceed the limit, and new runs are rejected
  until it falls back. After all of them finish, the count returns exactly to 0, with no drift.
  This is verified by comparing the gauge with `SELECT count(*) … WHERE owner_instance = :me`.
- [ ] **AC6:** Given the `step-io` queue is full, when a step attempt is submitted, then it
  records outcome `BULKHEAD_FULL` with `error_class = TaskRejectedException`, and the retry
  policy applies. No exception escapes the coordinator.
- [ ] **AC7:** Given an idempotent replay (US-4.7) while the node is at its limit, then the
  replay is still served.

## Technical details
**Components**
- `EndpointRuntime.bulkhead` is a `SemaphoreBulkhead` built from the endpoint
  `resilience.bulkhead` or the defaults. `ResilientHttpClient` uses
  `Bulkhead.decorateCompletionStage` directly around the HTTP call (US-5.3). A
  `BulkheadFullException` maps to `BULKHEAD_FULL`, recorded as a `step_attempt` row with no
  request sent.
- `engine.AdmissionControl` holds an `AtomicInteger drivenRuns`, changed only by
  `RunLeaseService.onClaimed`, `onCreated`, `onReleased` and `onLeaseLost`.
  `tryAdmitNew()` rejects when `drivenRuns >= max`. A reconciliation every
  `apiorch.lease.renew-interval-ms` sets the counter from the set of leases this node renewed
  (US-5.5). It never uses `Semaphore.release()`.
- The `step-io` `RejectedExecutionHandler` throws `TaskRejectedException`, which `StepExecutor`
  turns into a `BULKHEAD_FULL` attempt.

**API:** `429` problem `admission-rejected`, with `Retry-After: 1` and `detail: "apiorch is at
capacity on this node; retry later"`.

**Configuration:** `apiorch.resilience.defaults.bulkhead.max-concurrent-calls` (25),
`apiorch.resilience.defaults.bulkhead.max-wait-ms` (0), `apiorch.admission.max-in-flight-runs`
(500), and the endpoint `resilience.bulkhead`.

**Metrics:** `resilience4j_bulkhead_available_concurrent_calls{name}`, `apiorch.runs.inflight`
(gauge = `drivenRuns`) and `apiorch.admission.rejected`.

**Logging:** WARN `bulkhead full endpoint=score-svc maxConcurrentCalls=5`; WARN `admission
rejected driven=50`.

**Test notes**
- `BulkheadIT` uses a `ServeEventListener` concurrency counter (AC1).
- `BulkheadIsolationIT` measures the baseline and then the saturated case in one test (AC2).
- `AdmissionControlIT` covers AC3, AC4 and AC7.
- `AdmissionNoDriftIT` uses recovery and wake-up claims at the limit, then compares the gauge
  with the database count (AC5).
- `ExecutorSaturationIT` covers AC6.
