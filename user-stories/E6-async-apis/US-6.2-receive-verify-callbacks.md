# US-6.2 Receive and verify callbacks
**Epic:** E6 Async steps · **Priority:** Must · **Size:** M · **Depends on:** US-6.1, US-5.5 · **Release:** R2

## User story
As a **Security Officer**, I want apiorch to accept a callback only when it carries a valid
token for that exact run, step and execution, and to process each callback exactly once and
never lose one, so that forged, replayed, stale or duplicated callbacks can never change a
run's outcome, and genuine ones always move it forward.

## Description
`POST /api/v1/callbacks/{runId}/{stepId}?t=<token>` (or header `X-Callback-Token`) is
authenticated only by the HMAC token (US-6.1), through its own security chain (US-1.2). For
each callback, apiorch does the following:
1. Enforces the size limit (US-1.1).
2. Verifies the token.
3. Locks the **run row first, then the step row** (README §5.1 rule 1).
4. Classifies the callback as `ACCEPTED`, `DUPLICATE`, `LATE` or `REJECTED`, and records it in
   `callback_inbox`, masked.
5. For an accepted callback, evaluates `callback.successWhen`, sets the step to `SUCCEEDED` or
   `FAILED`, and **always sets `wakeup_requested = 1`** in the same transaction.
6. Returns quickly.

Because the callback holds the run lock and sets the durable wake-up flag, it can't be lost
while the driving node is deciding to wait. That node re-reads the steps and the flag under the
same lock (US-6.3).

**Out of scope:** what happens to the run afterwards (US-6.3) and timeouts (US-6.4).

## Acceptance criteria
- [ ] **AC1:** Given api4 is `AWAITING_CALLBACK` (execution 1) and a valid token, when the
  downstream posts `{"status":"DONE","docId":"D-9"}`, then:
  - the response is `200 {"status":"ACCEPTED"}`;
  - `steps.api4.body` and `steps.api4.headers` are stored;
  - with `callback.successWhen: "body.status = 'DONE'"`, api4 becomes `SUCCEEDED`;
  - `wakeup_requested` is set;
  - an inbox row `ACCEPTED` and the event `CALLBACK_RECEIVED` `{bodyBytes, outcome:"SUCCEEDED"}`
    are recorded.
- [ ] **AC2:** Given `successWhen` evaluates false, for example `{"status":"FAILED"}`, then api4 is
  `FAILED` with `CALLBACK_REPORTED_FAILURE`, its `onFailure` policy applies (US-4.6), and the
  response is still `200 {"status":"ACCEPTED"}`.
- [ ] **AC3:** Given a missing, malformed, expired or tampered token, or one whose `runId`,
  `stepId` or `execNo` doesn't match, when received, then the response is `401`
  `invalid-callback-token`, and no step state changes. Only a **metadata** inbox row `REJECTED`
  is written (reason, source IP, size, no body or headers). Events and inbox rows for rejections
  are rate-limited to 10 per run per minute, so a flood can't bloat the database or contend on
  the run row.
- [ ] **AC4:** Given the same callback is delivered twice for the same execution, when the second
  arrives, then the response is `200 {"status":"DUPLICATE"}`, an inbox row `DUPLICATE` and the
  event `CALLBACK_DUPLICATE` are recorded, and the state is unchanged.
- [ ] **AC5:** Given the step is `CALLBACK_TIMED_OUT`, `CANCELLED` or `COMPENSATED`, the run is
  terminal, or the token is for an **earlier execution** of a resumed step (US-5.8), when a valid
  callback arrives, then the response is `200 {"status":"LATE"}`, an inbox row `LATE` (masked
  body kept) and the event `CALLBACK_LATE` are recorded, and the state is unchanged. The body is
  retrievable through US-7.4.
- [ ] **AC6 (early callback):** Given a downstream that calls back **before** apiorch processed
  its 202 ack (step still `RUNNING`, same execution), when the callback arrives, then it is
  recorded `ACCEPTED` and applied as soon as the ack is processed (US-6.1). The step ends
  `SUCCEEDED` and never `CALLBACK_TIMED_OUT`.
- [ ] **AC7 (no lost callback):** Given the driving node is evaluating quiescence for the run at
  the same moment, in any interleaving, then either the driver sees api4 `SUCCEEDED` and
  continues, or the driver releases first and the callback finds `owner_instance IS NULL`. In
  the second case, the run is claimable through `wakeup_requested` (README §5.1). This is proven
  by a latch-controlled IT covering both orders.
- [ ] **AC8:** Given a callback for a `SYNC` step, the response is `409` `callback-not-expected`.
  Given an unknown run or step with an otherwise valid token, it is `404` `run-not-found`. Given a
  body over `apiorch.callback.max-body-bytes`, it is `413` `payload-too-large`.

## Technical details
**API contract:** `POST /api/v1/callbacks/{runId}/{stepId}?t=<token>`
- **Token:** query `t` or header `X-Callback-Token`. If both are present, the header wins.
- **Body:** any content type. JSON is parsed; anything else is stored as a string.
- **Responses:** `200 {"status":"ACCEPTED|DUPLICATE|LATE"}`, `401`, `404`, `409` and `413`.
  2xx is returned for duplicate and late callbacks so downstream retry loops stop.

**Components** (`com.crisil.apiorch.callback`)
- `CallbackController` calls `CallbackTokenService.verify(t, runId, stepId)`, which compares in
  constant time with `MessageDigest.isEqual`, then calls `CallbackService.receive(...)`.
- `CallbackService.receive` runs in one transaction, with deadlock retry (README §5.1):
  1. Lock the `pipeline_run` row, then the `step_execution` row, with the dialect row lock
     (`SqlDialect`: SQL Server `WITH (UPDLOCK, ROWLOCK)`, H2 `FOR UPDATE`; ADR-003 F3, F4, rule 4),
     bounded by `SqlDialect.withLockTimeout` (ADR-003 F5).
  2. Classify, using `exec_no` from the token:
     - `ACCEPTED` if the token's `execNo` equals the step's current `exec_no`, the step is
       `AWAITING_CALLBACK` (or `RUNNING` in async mode, which is the early case), and there is
       no prior `ACCEPTED` row for that `exec_no`;
     - `DUPLICATE` if such an `ACCEPTED` row exists;
     - `LATE` otherwise.
  3. Insert the `callback_inbox` row (masked and size-capped, US-7.3) with `exec_no` and the
     event's `seq`.
  4. If `ACCEPTED` and the step is `AWAITING_CALLBACK`: write `run_context(<stepId>)` (ack kept;
     body and headers from the callback), evaluate `successWhen`, and apply the result through
     `FailurePolicyApplier`. If it gives `STOP_RUN`, persist `stop_reason`.
  5. Set `wakeup_requested = 1`.
  6. Commit. `afterCommit` calls `RunCoordinator.tryClaimAndAdvance(runId)` (US-6.3).
- For an early `ACCEPTED` callback, step 4 runs later, inside `AsyncCallbackStepExecutor`
  (US-6.1).
- `RejectionLimiter` is an in-memory token bucket per `runId`, used for AC3.

**Data:** Flyway `V8__callback_inbox.sql` creates `callback_inbox` (README §10). The raw body
needed for mapping is kept only in `run_context` (encrypted, US-7.6); the inbox keeps the masked
copy and `body_bytes`.

**Security:** permitted without JWT (US-1.2, chain 1). Ingress rate limiting per source IP is
recommended and documented in the runbook (US-8.4). The token is never logged.

**Configuration:** `apiorch.callback.max-body-bytes` (1048576).

**Test notes**
- `CallbackReceiveIT` covers AC1 and AC2.
- `CallbackSecurityIT` covers each token defect, metadata-only rejection and the rate limit
  (AC3).
- `CallbackDuplicateIT` covers AC4.
- `CallbackLateIT` covers a timed-out step, a terminal run and an old execution (AC5).
- `EarlyCallbackIT` covers AC6.
- `CallbackQuiescenceRaceIT` uses a test hook latch between the coordinator's quiescence read
  and its release, and asserts the run completes in both orders (AC7). It locks rows, so it is
  `slow`-tagged and runs on Testcontainers SQL Server (ADR-003 rule 6).
- `CallbackErrorsIT` covers AC8.
