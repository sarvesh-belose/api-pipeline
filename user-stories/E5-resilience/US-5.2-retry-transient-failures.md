# US-5.2 Retry transient failures safely
**Epic:** E5 Resilience · **Priority:** Must · **Size:** M · **Depends on:** US-5.1, US-7.2 · **Release:** R1

## User story
As an **Operations Engineer**, I want transient downstream failures (network errors, timeouts,
502/503/504, 429) retried automatically with backoff, but never in a way that could duplicate a
non-idempotent operation or overrun the run deadline, so that brief outages on other
infrastructure don't fail business transactions or corrupt data.

## Description
Each step execution runs its attempts through a Resilience4j `Retry`, built from the effective
policy: step, then pipeline `defaults`, then endpoint (resolved at attempt time), then global.
The retry works as follows:
- **Classification:** decides whether a failure is retryable or permanent (table below).
- **Idempotency gate:** a request that was **sent** is retried only if the step is
  `idempotent`. Failures **before** sending (`CONNECT_ERROR`, `BULKHEAD_FULL`) are always safe
  to retry.
- **Backoff:** `FIXED` or `EXPONENTIAL_JITTER` (full jitter). A `Retry-After` on 429 or 503 is
  honoured up to `maxMs`.
- **Budget check before every attempt:** if the remaining segment budget is ≤ 100 ms, the
  attempt is not made and the result is a terminal, non-retryable `DEADLINE_EXCEEDED`. The
  interval function only computes the delay, capped by the budget, and never throws.
- **Non-blocking waits:** delays are scheduled on the `apiorch-retry` scheduler, and attempts
  run on `step-io`, so no thread sleeps and no `step-io` task waits on another.

Every attempt of a step execution carries the same `Idempotency-Key: {runId}:{stepId}:{execNo}`,
so downstream systems can de-duplicate. `execNo` changes only on operator resume (US-5.8).

**Out of scope:** circuit breaking (US-5.3), bulkheads (US-5.4), and the token-refresh repeat on
401 (US-2.2, which is part of one attempt).

## Acceptance criteria
- [ ] **AC1:** Given api2 (`GET`, idempotent by default) responds 503, 503, then 200, with
  `maxAttempts: 3`, when it runs, then three attempts are made, and the step is `SUCCEEDED` with
  `attempts = 3`. The events `ATTEMPT_FAILED` ×2 and `RETRY_SCHEDULED` ×2
  `{attempt, delayMs, reason: "HTTP_503"}` are recorded.
- [ ] **AC2:** Given a `400`, `401`, `403`, `404`, `409` or `422` response, when received, then
  there is no retry, and the step fails at once with `HTTP_ERROR` and `attempts = 1`.
- [ ] **AC3:** Given a POST step with `idempotent: false` that gets `503` after the request was
  sent, then it is **not** retried. Given the same step gets `CONNECT_ERROR` (nothing sent), it
  **is** retried. Given `idempotent: true`, the `503` is retried.
- [ ] **AC4:** Given `429` with `Retry-After: 1`, when the retry is scheduled, then the next
  attempt starts at least 1000 ms later. A `Retry-After` above `retry.maxMs` is capped at
  `maxMs`.
- [ ] **AC5:** Given `EXPONENTIAL_JITTER` with `initialMs: 200` and `maxMs: 2000`, when attempts
  fail repeatedly, then the delay before attempt *n* is uniformly random in
  `[0, min(2000, 200·2^(n-1))]`. This is verified with a seeded random.
- [ ] **AC6:** Given a retry whose delay leaves 100 ms or less of segment budget, when the next
  attempt is due, then it is not made. The step completes with outcome `DEADLINE_EXCEEDED`
  (handled as in US-5.1), and the step never hangs. This is verified with a short `runTimeoutMs`
  and a WireMock 503 loop: the step reaches a terminal state within `runTimeoutMs` + 1 s.
- [ ] **AC7:** Given `apiorch.executor.step-io.size=2` and 20 steps of different runs all in
  backoff, then other runs still execute their attempts, because no thread is held during
  backoff.
- [ ] **AC8:** Given every attempt of a step execution, then each request has the identical
  `Idempotency-Key: {runId}:{stepId}:{execNo}`, and `step_execution.attempts` equals the number
  of attempts made, including rejected ones. Each attempt has exactly one `step_attempt` row
  (US-7.2).

## Technical details
**Classification** (`http.RetryClassifier`)
| Outcome | Default retryable | Needs `idempotent` |
|---|---|---|
| `CONNECT_ERROR` (refused, connect timeout, DNS) | ✔ | ✖ (not sent) |
| `BULKHEAD_FULL` (US-5.4) | ✔ | ✖ (not sent) |
| `TIMEOUT` (sent, no response in time, not budget-capped) | ✔ | ✔ |
| `IO_ERROR` (reset or broken pipe after send) | ✔ | ✔ |
| HTTP 502, 503, 504 | ✔ | ✔ |
| HTTP 429 | ✔ (honour `Retry-After`) | ✔ |
| HTTP 408, 500 | only if listed in `retryOn` | ✔ |
| `DEADLINE_EXCEEDED`, other 4xx, `SUCCESS_CHECK_FAILED`, `AUTH_ERROR`, `EXPRESSION_ERROR`, `RESPONSE_TOO_LARGE`, `CIRCUIT_OPEN` | ✖ never | — |

The `retryOn` tokens are `CONNECT_ERROR`, `IO_ERROR`, `TIMEOUT`, `408`, `429`, `500`, `502`,
`503`, `504` and `5XX`. The default is `[CONNECT_ERROR, IO_ERROR, TIMEOUT, 429, 502, 503, 504]`,
and `BULKHEAD_FULL` is always retryable.

**Components**
- `http.RetryFactory.forExecution(compiledStep, endpointRuntime, attemptContext)` builds a
  **per-execution** `Retry` from a cached `RetryConfig` (one per endpoint and policy hash). Retry
  instances are lightweight, and per-execution instances let the interval function see that
  execution's deadline. The config has:
  - `maxAttempts`;
  - `intervalBiFunction`: full jitter, `Retry-After` parsing in seconds or HTTP-date, and the
    budget cap;
  - `retryOnResult(AttemptResult::isRetryable)` and `retryOnException(RetryClassifier::isRetryable)`.
- `ResilientHttpClient.execute` runs `Retry.decorateCompletionStage(retry, retryScheduler,
  attemptSupplier)`. The `attemptSupplier`:
  1. checks the budget (AC6), returning a completed stage with a terminal `DEADLINE_EXCEEDED`
     result if exhausted;
  2. otherwise submits **one** blocking HTTP call to `step-io` and returns its
     `CompletableFuture`.

  The circuit breaker and bulkhead wrap this supplier (US-5.3, US-5.4).
- `retryScheduler` is the `apiorch-retry` `ScheduledExecutorService` with 2 threads, used only to
  schedule the next attempt, never to run it.

**Data:** `step_execution.attempts`. One `step_attempt` row per attempt (US-7.2), with
`next_retry_delay_ms` on retried failures.

**Configuration:** `apiorch.resilience.defaults.retry.*`, endpoint `resilience.retry`, pipeline
`defaults.retry`, and step `retry`.

**Metrics:** `apiorch.step.attempts{endpoint,outcome}` and
`apiorch.retry.scheduled{endpoint,reason}` (US-7.5). Per-execution Retry instances are **not**
bound to `TaggedRetryMetrics`, which would create unbounded meters.

**Logging:** WARN `attempt failed attempt=1 outcome=HTTP_ERROR httpStatus=503`; INFO `retry
scheduled attempt=2 delayMs=173 reason=HTTP_503`.

**Test notes**
- `RetryClassifierTest` covers the full table.
- `RetryIT` uses WireMock scenarios: 503→503→200, the 4xx set, POST with and without
  `idempotent`, and `Fault.CONNECTION_RESET_BY_PEER` (AC1–AC4).
- `JitterBackoffTest` covers AC5.
- `RetryBudgetIT` covers AC6.
- `RetryNoThreadBlockingIT` covers AC7.
- `IdempotencyHeaderIT` covers AC8.
