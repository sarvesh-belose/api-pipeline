# US-5.8 Operator resume of a failed or timed-out run
**Epic:** E5 Resilience · **Priority:** Must · **Size:** M · **Depends on:** US-5.7 · **Release:** R2

## User story
As an **Operations Engineer**, I want to resume a failed or timed-out run from the point of
failure once the downstream issue is fixed, keeping every step that already succeeded, so that
I can complete business transactions without re-running steps that already changed data
elsewhere.

## Description
**Resume** applies to `FAILED` and `TIMED_OUT` runs. It is refused when:
- **any step is `UNKNOWN`** (`409` `unresolved-steps`): resolve those first (US-5.7);
- **the working context was purged** (`409` `context-purged`), because the retention window for
  resumable runs passed (`apiorch.retention.resumable-context-days`, US-7.8);
- **the run was compensated**, since nothing is left to resume.

Otherwise it does the following:
1. Resets the steps in `FAILED`, `CANCELLED`, `CALLBACK_TIMED_OUT` or
   `SKIPPED(DEPENDENCY_FAILED)` to `PENDING`, with **`exec_no + 1`**.
2. Keeps the outputs of steps that succeeded.
3. Clears `stop_reason`, the `error_*` fields, `completed_at` and `ended_at`.
4. Starts a **new segment** (US-5.1) on the **same pinned version**.

The new `exec_no` changes:
- the outbound `Idempotency-Key`, so the downstream doesn't replay a cached failure;
- the callback token for async steps, so a late callback from the old execution can't complete
  the new one (US-6.2).

`attempt_no` keeps increasing across executions, so attempt numbers stay unique per step.

**Out of scope:** resuming on a newer pipeline version, and editing inputs.

## Acceptance criteria
- [ ] **AC1:** Given a run `FAILED` at api3 (HTTP 503) where api1 and api2 succeeded, when an
  operator sends `POST /api/v1/runs/{id}/resume` with `{"reason":"KYC fixed INC-4411"}`, then:
  - api3 and the steps cancelled or skipped after it return to `PENDING` with `exec_no = 2`;
  - api1 and api2 are **not** re-called, and later steps use their stored responses;
  - the run goes to `RUNNING`, then on to completion;
  - `resume_count` is incremented, and the event `RUN_RESUMED` `{actor, reason, resetSteps}` is
    recorded.
- [ ] **AC2:** Given the resumed api3, when it is sent, then its `Idempotency-Key` is
  `{runId}:api3:2`, and its first new attempt has `attempt_no` one higher than the last attempt
  of execution 1.
- [ ] **AC3:** Given a run with any step `UNKNOWN`, when resume is requested, then the response
  is `409` `unresolved-steps`, listing the steps. After resolving them (US-5.7), the resume
  succeeds.
- [ ] **AC4:** Given a run in `COMPLETED`, `COMPLETED_WITH_ERRORS`, `COMPENSATED`,
  `COMPENSATING`, `COMPENSATION_FAILED`, `RUNNING`, `WAITING_CALLBACK` or `CANCELLED`, when resume
  is requested, then the response is `409` `illegal-run-state`.
- [ ] **AC5:** Given a `FAILED` run whose context was purged (`context_purged_at` set), when
  resume is requested, then the response is `409` `context-purged`.
- [ ] **AC6:** Given an async step that had `CALLBACK_TIMED_OUT` and is resumed, when a callback
  for **execution 1** arrives after the resume, then it is recorded `LATE` and ignored. Only a
  callback carrying the execution-2 token completes the step.
- [ ] **AC7:** Given a resumed run that later finishes, then `GET /api/v1/runs/{id}` shows fresh
  `completedAt`/`endedAt`, no stale `error_*` from the first execution, and `resumeCount: 1`. The
  timeline shows both executions in order.

## Technical details
**API contract:** `POST /api/v1/runs/{runId}/resume` takes `{"reason": "1–500 chars"}` and
returns `200` with the run resource (status `RUNNING`).

**Components** (`com.crisil.apiorch.engine.RunOperatorService.resume`), in one transaction:
1. Lock the run row, check the status, check for `UNKNOWN` steps, and check `context_purged_at`.
2. Claim the lease (US-5.5).
3. Reset the steps: `status = PENDING`, `exec_no = exec_no + 1`, attempt counters preserved.
4. Clear `stop_reason`, `error_*`, `completed_at` and `ended_at`, and increment `resume_count`.
5. Start a segment (US-5.1).
6. Record `RUN_RESUMED`.

`afterCommit` then calls `RunCoordinator.advance`.

**Outbound keys and tokens:** `Idempotency-Key: {runId}:{stepId}:{execNo}` (US-5.2), and the
callback token binds `execNo` (US-6.1).

**Data:** `step_execution.exec_no`; `step_attempt.exec_no` and `attempt_no` (unique per run,
step and kind); `pipeline_run.resume_count`, `segment_started_at` and `context_purged_at`.

**Logging:** WARN `operator action=RESUME actor=… reason=… resetSteps=[…]`.

**Test notes**
- `ResumeIT` fixes a WireMock 503 so it returns 200, then resumes. It checks call counts for the
  steps that succeeded, `exec_no`, the key and attempt numbering (AC1, AC2).
- `ResumeUnresolvedIT` covers AC3.
- `ResumeIllegalStateIT` covers AC4.
- `ResumeContextPurgedIT` covers AC5.
- `ResumeAsyncTokenIT` covers AC6.
- `ResumeResourceIT` covers AC7.
