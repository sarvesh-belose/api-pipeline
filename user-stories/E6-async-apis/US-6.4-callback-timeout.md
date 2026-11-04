# US-6.4 Time out missing callbacks and apply the failure policy
**Epic:** E6 Async steps · **Priority:** Must · **Size:** S · **Depends on:** US-6.2 · **Release:** R2

## User story
As an **Operations Engineer**, I want an async step whose callback never arrives to time out
after its configured window and follow its failure policy, so that runs never wait forever on
a downstream system that lost the request.

## Description
A ShedLock-guarded `CallbackTimeoutSweeper` runs every
`apiorch.callback.timeout-sweep-interval-ms`. It finds steps in `AWAITING_CALLBACK` whose
`callback_deadline_at` has passed and marks them `CALLBACK_TIMED_OUT`. It then applies the
step's `onFailure`:

| Policy | Result |
|---|---|
| `CONTINUE` | Dependents become `SKIPPED(DEPENDENCY_FAILED)` |
| `FALLBACK` | `SUCCEEDED` with `fallback_applied`; dependents run with the fallback body |
| `FAIL_RUN` | Allowed only for blocking steps outside the post-completion subgraph (US-3.2). `stop_reason=FAIL_RUN` is persisted, and the run becomes `FAILED`, or `COMPENSATING` (US-5.6) |

The timeout transaction locks the run row first, then the step row (README §5.1). It sets
`wakeup_requested = 1`, exactly as a callback does (US-6.2/6.3). A callback that arrives
after the timeout is recorded as `LATE` (US-6.2).

**Out of scope:** extending or renewing a callback deadline.

## Acceptance criteria
- [ ] **AC1:** Given api4 is `AWAITING_CALLBACK` with `callback.timeoutMs: 5000` and no callback
  is sent, when 5 s plus at most one sweep interval have elapsed, then api4 is
  `CALLBACK_TIMED_OUT` with error code `CALLBACK_TIMED_OUT`, and the event `CALLBACK_TIMED_OUT`
  `{deadlineAt}` is recorded.
- [ ] **AC2:** Given api4 is non-blocking with `onFailure: CONTINUE` and the run is
  `COMPLETED_PENDING_ASYNC` with dependent api5, when api4 times out, then api5 becomes
  `SKIPPED(DEPENDENCY_FAILED)` and the run becomes `COMPLETED_WITH_ERRORS`, with `errors[]`
  containing `{stepId:"api4", code:"CALLBACK_TIMED_OUT"}`.
- [ ] **AC3:** Given api4 with `onFailure: FALLBACK` and `fallback: "{ 'docId': null, 'status':
  'PENDING_MANUAL' }"`, when it times out, then api4 is `SUCCEEDED` with `fallback_applied`,
  api5 runs with the fallback body, and the run ends `COMPLETED_WITH_ERRORS`.
- [ ] **AC4:** Given a blocking async step (`blocksRunCompletion: true`) with `onFailure:
  FAIL_RUN` and the run in `WAITING_CALLBACK`, when it times out, then the run becomes `FAILED`
  with `error_step_id` set to that step, or `COMPENSATING` if eligible steps exist. A sync caller
  that is still waiting gets `200` with that status.
- [ ] **AC5:** Given 500 steps across runs time out at the same moment, when the sweeper runs,
  then all 500 are marked `CALLBACK_TIMED_OUT` within 3 sweep intervals, each step in its own
  transaction. One failing step doesn't block the others, and only one node sweeps at a time
  (ShedLock).
- [ ] **AC6:** Given a callback that arrives 1 s after the timeout was applied, when received,
  then the response is `200 {"status":"LATE"}`, and the step stays `CALLBACK_TIMED_OUT` (US-6.2).

## Technical details
**Components** (`com.crisil.apiorch.callback`)
- `CallbackTimeoutSweeper` is a `@Scheduled(fixedDelayString =
  "${apiorch.callback.timeout-sweep-interval-ms}")` task with
  `@SchedulerLock(name="callback-timeout-sweeper", lockAtMostFor="PT2M")`. Each cycle:
  1. `SELECT TOP (100) run_id, step_id FROM step_execution WHERE status='AWAITING_CALLBACK' AND
     callback_deadline_at < SYSUTCDATETIME() ORDER BY callback_deadline_at`, repeated
     until no rows remain or the cycle has run for 60 s.
  2. For each row, call `CallbackTimeoutHandler.timeout(runId, stepId)` in a new transaction
     with deadlock retry:
     - lock the **run row, then the step row** with the dialect row lock (ADR-003 rule 4), and
       re-check the status, since a callback may
       have won the race;
     - set `CALLBACK_TIMED_OUT`;
     - apply `FailurePolicyApplier` (US-4.6), persisting `stop_reason` on `STOP_RUN`;
     - record the event;
     - set `wakeup_requested = 1`;
     - `afterCommit` calls `RunCoordinator.tryClaimAndAdvance`.
- The index (`status`, `callback_deadline_at`) on `step_execution` (README §10) supports the
  query.

**Data:** `step_execution.status = CALLBACK_TIMED_OUT`, `error_code = CALLBACK_TIMED_OUT` and
`callback_deadline_at`.

**Configuration:** `apiorch.callback.timeout-sweep-interval-ms` (10000), and the step's
`callback.timeoutMs` (required for async steps; the validator enforces 1 000–604 800 000 ms).

**Metrics:** `apiorch.callback.timeouts{pipeline,step}`.

**Logging:** WARN `callback timed out stepId=api4 deadlineAt=… policy=CONTINUE`.

**Test notes**
- `CallbackTimeoutIT` uses a short `timeoutMs` (1 s) and `timeout-sweep-interval-ms=200`. It
  covers CONTINUE, FALLBACK and FAIL_RUN, with and without compensation (AC1–AC4), and a late
  callback (AC6).
- `CallbackTimeoutBatchIT` seeds 500 awaiting steps with past deadlines. It checks the batching
  and that a single injected failure is isolated (AC5).
- `CallbackVsTimeoutRaceIT` fires a callback and the sweeper concurrently. Exactly one outcome
  wins and the other records `LATE` or no change. It is `slow`-tagged and runs on Testcontainers
  SQL Server (ADR-003 rule 6).
