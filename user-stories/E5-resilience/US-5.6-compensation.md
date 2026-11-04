# US-5.6 Compensate completed steps when a run fails
**Epic:** E5 Resilience · **Priority:** Should · **Size:** M · **Depends on:** US-4.6, US-5.1, US-5.5, US-6.1, US-7.2 · **Release:** R3

## User story
As a **Pipeline Administrator**, I want to give a step an optional "undo" API call that apiorch
makes when the run fails, so that a partially executed transaction (for example a customer
created but the account never opened) is cleaned up automatically on the systems that were
already changed.

## Description
Compensation is optional **per step**. A step may declare
`compensate: {endpoint?, method, path, query?, headers?, body?}`.

**When it runs:** the run fails under `FAIL_RUN`, or its normal segment times out (US-5.1), or an
operator cancels with `compensate=true` (US-5.7). If at least one eligible step exists, the run
moves to **`COMPENSATING`**. Post-completion work never triggers compensation (README §7).

**Eligibility** is decided **first**, before any step is cancelled:
- steps that are `SUCCEEDED` without `fallback_applied`, including steps that finished in flight
  after the run started stopping;
- async steps in `AWAITING_CALLBACK` that declare `compensate`, because their downstream
  accepted the work. They go straight to compensation and never pass through `CANCELLED`.

**Order and budget:** the `CompensationRunner` calls each eligible step's compensation
**sequentially, in reverse completion order** (`completion_seq DESC`). `completion_seq` is the
`seq` of the step's `STEP_SUCCEEDED` or `ASYNC_ACCEPTED` event, so it is never NULL and is
identical on every database. Compensation runs in **its own segment**, with
`apiorch.compensation.deadline-ms`, so a run that timed out can still be cleaned up. Each call
has its own retries.

**Outcome:** if all succeed, the run is `COMPENSATED`. If any fails, the remaining compensations
are still attempted (best effort), and the run ends `COMPENSATION_FAILED`.

**Out of scope:** compensating compensations, and parallel compensation.

## Acceptance criteria
- [ ] **AC1:** Given api1 (create customer) and api2 (create KYC case) both have `compensate`, and
  api3 fails under `FAIL_RUN`, when the failure is processed, then the run goes `RUNNING →
  COMPENSATING`. api2's compensation is called **before** api1's (WireMock order), both steps
  become `COMPENSATED`, and the run ends `COMPENSATED` with `error_code` keeping the original
  cause (`STEP_FAILED`, `error_step_id = api3`).
- [ ] **AC2:** Given api1's compensation `{"method":"DELETE","path":"/customers/{steps.api1.body.id}"}`
  and api1 returned `{"id":"C-77"}`, when compensation runs, then WireMock receives
  `DELETE /customers/C-77` with `Idempotency-Key: {runId}:api1:{execNo}:compensate`,
  `X-Correlation-Id` and the endpoint auth.
- [ ] **AC3:** Given api2's compensation returns `500` until its retries are exhausted, when the
  runner continues, then api1's compensation is still attempted. api2 ends with
  `compensation_status = FAILED` and api1 with `SUCCEEDED`. The run ends `COMPENSATION_FAILED`,
  and `apiorch.compensation.failed` is incremented.
- [ ] **AC4:** Given steps without `compensate`, steps with `fallback_applied`, and `SKIPPED`,
  `CANCELLED`, `FAILED` or `UNKNOWN` steps, when compensation runs, then they are not called, and
  their `compensation_status` is `NOT_REQUIRED`. `UNKNOWN` steps are listed in the run's
  `errors[]` for operator review. Given no eligible steps, the run goes straight to `FAILED`.
- [ ] **AC5:** Given a run whose segment deadline passes (US-5.1) with eligible steps, when it
  stops, then it enters `COMPENSATING` with a **new** segment of
  `apiorch.compensation.deadline-ms`, and compensation calls are made with normal attempt
  timeouts. Given an async step api4 in `AWAITING_CALLBACK` with `compensate`, it is compensated
  first, because it was accepted last, and ends `COMPENSATED`. A callback arriving for it later
  is recorded as `LATE`.
- [ ] **AC6:** Given every compensation attempt, then it is recorded as `step_attempt` with
  `kind = COMPENSATION` and its own `COMPENSATION_REQUEST_SENT` event (US-7.2). The events
  `COMPENSATION_STARTED`, `COMPENSATION_STEP_SUCCEEDED` / `COMPENSATION_STEP_FAILED` and
  `COMPENSATION_COMPLETED` / `COMPENSATION_FAILED` appear in order.
- [ ] **AC7:** Given a node crash during `COMPENSATING`, when the run is recovered (US-5.5), then
  compensation continues with the steps whose `compensation_status` is still `PENDING`. A step
  that was mid-compensation is retried, because compensations are required to be idempotent
  (documented in US-8.3).
- [ ] **AC8:** Given a sync caller, when the run compensates within `syncWaitMs`, then it
  receives `200` with `status` `COMPENSATED` or `COMPENSATION_FAILED`, and the partial output
  (US-4.6).
- [ ] **AC9 (feature gate lifted):** Given this story is delivered, then `compensate` no longer
  reports `FEATURE_NOT_AVAILABLE` (`FeatureRegistry.COMPENSATION = true`).

## Technical details
**Components** (`com.crisil.apiorch.step`)
- `CompensationRunner.compensate(run)` works in fenced transactions, one step at a time:
  1. Compute eligibility (above) **before** touching other steps. Set `compensation_status =
     PENDING` on eligible steps and `NOT_REQUIRED` on the rest. Move the remaining
     `AWAITING_CALLBACK` steps that aren't eligible to `CANCELLED`.
  2. Set the run to `COMPENSATING` and start a segment with `apiorch.compensation.deadline-ms`
     (US-5.1).
  3. For each eligible step, ordered by `completion_seq DESC`, build the request with
     `RequestBuilder`, using the run context (ancestors plus `steps.<self>`) and `error`.
     Execute it through `ResilientHttpClient`, with the step's retry policy (treated as
     idempotent), the circuit breaker and the bulkhead. Record the outcome. A success sets
     the step status to `COMPENSATED`.
  4. Finalize as `COMPENSATED` or `COMPENSATION_FAILED`.
- `RunFinalizer` (US-4.6), `DeadlineScheduler` (US-5.1) and `RunOperatorService` (US-5.7) call
  `CompensationRunner.hasEligibleSteps(run)` before choosing `FAILED`, `TIMED_OUT` or
  `CANCELLED`.

**Data:** `step_execution.compensation_status` (`NOT_REQUIRED | PENDING | SUCCEEDED | FAILED`)
and `completion_seq`; `step_attempt.kind = COMPENSATION`, with `event_seq`. The outbound key is
`Idempotency-Key: {runId}:{stepId}:{execNo}:compensate`.

**Validation (US-3.2):** `compensate.endpoint` must exist, its expressions compile, and its
references obey the ancestor rule plus `steps.<self>`.

**Configuration:** `apiorch.compensation.deadline-ms` (300000).

**Logging:** WARN `compensation started steps=[api4,api2,api1]`; ERROR `compensation failed
stepId=api2 attempts=3 lastStatus=500`.

**Metrics:** `apiorch.compensation.runs{outcome}` and `apiorch.compensation.failed`.

**Test notes**
- `CompensationIT` covers AC1 and AC2.
- `CompensationBestEffortIT` covers AC3.
- `CompensationEligibilityIT` covers AC4, including a step that succeeded in flight after the
  stop.
- `CompensationOnTimeoutIT` covers the new segment and an `AWAITING_CALLBACK` step (AC5).
- `CompensationEventsIT` covers AC6.
- `CompensationRecoveryIT` covers AC7.
- `CompensationSyncResponseIT` covers AC8.
