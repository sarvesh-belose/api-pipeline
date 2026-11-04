# US-6.1 Call an async step with a signed callback URL
**Epic:** E6 Async steps · **Priority:** Must · **Size:** M · **Depends on:** US-4.3, US-5.2 · **Release:** R2

## User story
As a **Pipeline Administrator**, I want to mark a step as async, so that apiorch sends the
request with a unique, signed callback URL, records the acknowledgement, and moves on to steps
that don't need its result, so that a slow downstream process (for example document generation)
doesn't hold up the rest of the pipeline.

## Description
A step with `mode: ASYNC_CALLBACK` is sent like a sync step: mapping, auth, retries, circuit
breaker and bulkhead all apply. In addition, apiorch generates a **callback URL** that is unique
to the run, the step **and the step's execution** (`exec_no`, US-5.8), and carries an
HMAC-signed token:
- The URL is sent in the `X-Callback-Url` header.
- It is also available to expressions as `callback.url`, for APIs that expect it in the body.

A **2xx acknowledgement** moves the step to `AWAITING_CALLBACK` and stores the ack response as
`steps.<id>.ack`. The callback deadline is set, and `completion_seq` is assigned from the
`ASYNC_ACCEPTED` event, which compensation order needs (US-5.6). Steps that don't depend on the
async step continue immediately; those that depend on it wait for the callback (US-6.2/6.3).

This story turns on `FeatureRegistry.ASYNC_STEPS` (US-3.2).

**R2 entry criterion:** the inventory of real async APIs (README §3, Q5) confirms which ones
accept a per-request callback URL. Those that need a fixed registered URL use US-6.6.

**Out of scope:** receiving callbacks (US-6.2), completion semantics (US-6.3), timeouts (US-6.4),
and polling-based or broker-based async APIs.

## Acceptance criteria
- [ ] **AC1:** Given api4 with `"mode":"ASYNC_CALLBACK"` and `"callback":{"timeoutMs":600000}`,
  when it runs, then the outbound request carries
  `X-Callback-Url: {apiorch.callback.public-base-url}/api/v1/callbacks/{runId}/api4?t=<token>`
  and all standard outbound headers (US-4.3).
- [ ] **AC2:** Given api4's body `"{ 'docType': 'WELCOME', 'replyTo': callback.url }"`, when it
  runs, then `replyTo` equals the same callback URL as the header.
- [ ] **AC3:** Given the downstream replies `202 {"jobId":"J-1"}`, when the ack is processed, then:
  - api4 is `AWAITING_CALLBACK`;
  - `steps.api4.ack = {status:202, headers, body:{jobId:"J-1"}}` is stored;
  - `callback_deadline_at = dbNow + 600000`, `callback_kid` and `completion_seq` are set;
  - the event `ASYNC_ACCEPTED` `{deadlineAt, ackStatus: 202}` is recorded.
- [ ] **AC4:** Given api5 depends only on api2 and api3 depends on api4, when api4 is accepted,
  then api5 starts immediately, and api3 remains `PENDING` until api4's callback arrives.
- [ ] **AC5:** Given an ack that is non-2xx or fails at the transport level, when classified,
  then normal retry and failure handling applies (US-5.2, US-4.6). A POST ack is retried after
  sending only if the step is `idempotent: true`. A step whose ack request finally failed never
  enters `AWAITING_CALLBACK`.
- [ ] **AC9 (the callback wins over a failed ack):** Given the downstream received the request,
  processed it and called back, but **our** read of the ack timed out and the ack finally failed,
  so an `ACCEPTED` inbox row for this execution exists next to a failing ack, then the callback is
  authoritative: the step is reconciled to the callback's outcome (`SUCCEEDED` under a passing
  `callback.successWhen`), not left `FAILED`, and `ASYNC_ACK_SUPERSEDED_BY_CALLBACK` is recorded
  with the ack's error. The work really happened downstream, so reporting it as failed would be
  wrong and would trigger a needless compensation. The ack-failure path therefore checks for an
  `ACCEPTED` inbox row for the current `exec_no` inside its own fenced transaction before applying
  the failure policy.
- [ ] **AC6:** Given the token, when decoded, then it binds `kid`, `runId`, `stepId`, `execNo`
  and expiry. Its expiry is `callback_deadline_at + 24 h`, so late callbacks are still
  authenticated and recorded as `LATE`. Changing any part invalidates the HMAC.
- [ ] **AC7:** Given `apiorch.callback.public-base-url` is missing or not `https://` outside the
  `local` profile, when the app starts, then startup fails with a clear message.
- [ ] **AC8:** Given this story is delivered, then definitions using `mode: ASYNC_CALLBACK`,
  `callback` or `blocksRunCompletion` no longer report `FEATURE_NOT_AVAILABLE`.

## Technical details
**Token format** (`callback.CallbackTokenService`):
- `t = base64url(kid) "." base64url(runId) "." stepId "." execNo "." expEpochSeconds "." base64url(HMAC-SHA256(key[kid], kid|runId|stepId|execNo|exp))`.
- Keys are resolved through `SecretResolver` (US-2.2) from `apiorch.callback.keys.<kid>`, for
  example `env:APIORCH_SECRET_CALLBACK_K1`. Each key is at least 32 bytes, and new tokens use
  `apiorch.callback.active-kid`.
- Old kids stay valid while they remain configured, which allows key rotation.
- In logs the token appears only as `t=***` (US-7.3).

**Components**
- `step.AsyncCallbackStepExecutor` extends the `HttpStepExecutor` flow:
  1. Generates the token and URL for `(runId, stepId, execNo)`.
  2. Adds `callback.url` to the context.
  3. Sets `X-Callback-Url`.
  4. On a 2xx ack, in one fenced transaction: stores `run_context(<stepId>).ack`, sets
     `AWAITING_CALLBACK`, `callback_deadline_at`, `callback_kid` and `completion_seq`, and
     records `ASYNC_ACCEPTED`.
  5. In the same transaction, checks `callback_inbox` for an **early callback** for this
     `exec_no` (US-6.2). If one exists, it is applied immediately.
- `ReadySetCalculator` treats `AWAITING_CALLBACK` as unsatisfied, so dependents wait.
  `AWAITING_CALLBACK` is not "in flight" for `max-parallel-steps`.
- `config.CallbackProperties` holds `public-base-url`, `keys` and `active-kid`, validated at
  startup.

**Data:** `step_execution.callback_deadline_at`, `callback_kid`, `exec_no`, `completion_seq` and
`mode`; `run_context(<stepId>) = {"ack": {...}, "status": null, "body": null}` until the
callback arrives.

**Configuration:** `apiorch.callback.public-base-url`, `apiorch.callback.keys.<kid>` and
`apiorch.callback.active-kid`.

**Logging:** INFO `async step accepted ackStatus=202 deadlineAt=…`. The URL is logged with
`t=***`.

**Test notes**
- `CallbackTokenServiceTest` covers round-trip, tampering with each field including `execNo`,
  wrong kid, expiry, and two-kid rotation (AC6).
- `AsyncStepIT` uses a WireMock 202 ack and checks the header and `replyTo` (AC1, AC2), the
  persisted ack, deadline, seq and event (AC3), parallel continuation (AC4), and an ack of
  503→503→202 with `idempotent: true` plus the non-idempotent case without retries (AC5).
- `CallbackConfigValidationTest` covers AC7.
- `FeatureGateAsyncTest` covers AC8.
- `AckFailedAfterCallbackIT` has WireMock accept the request, fire the callback, and then hang past
  the read timeout so the ack exhausts its retries. It asserts the step ends `SUCCEEDED` with
  `ASYNC_ACK_SUPERSEDED_BY_CALLBACK`, the run completes, and no compensation runs (AC9).
