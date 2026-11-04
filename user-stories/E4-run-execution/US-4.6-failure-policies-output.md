# US-4.6 Apply per-step failure policies and assemble output
**Epic:** E4 Run execution · **Priority:** Must · **Size:** M · **Depends on:** US-4.3 · **Release:** R1

## User story
As a **Pipeline Administrator**, I want to decide for each step whether its failure stops the
run, is tolerated, or is replaced by a fallback value, and to define the combined output the
caller receives, including when the run fails, so that a failure in an optional API (for
example notifications) doesn't fail the whole transaction, and the caller always gets the
partial results that exist.

## Description
A step's `onFailure` applies once the step has finally failed, after retries (US-5.2):

| Policy | Step result | Effect on run |
|---|---|---|
| `FAIL_RUN` (default) | `FAILED` | `stop_reason = FAIL_RUN` is **persisted** in the same transaction (README §5.1 rule 7). No new step is scheduled, on any node, even after a crash. In-flight steps finish and keep their real outcome. Pending steps become `CANCELLED`. The run becomes `FAILED`, or `COMPENSATING` if eligible compensations exist (US-5.6) |
| `CONTINUE` | `FAILED` | Dependents become `SKIPPED(DEPENDENCY_FAILED)`; other branches continue; the run ends `COMPLETED_WITH_ERRORS` |
| `FALLBACK` | `SUCCEEDED` with `fallback_applied = 1` | `steps.<id>.body` = `fallback` result; dependents run with it; the run ends `COMPLETED_WITH_ERRORS` |

Post-completion steps can't use `FAIL_RUN` (US-3.2). Their failures always lead to
`COMPLETED_WITH_ERRORS` (README §7).

The run's **output** is the `output` JSONata expression, evaluated **whenever the run is done for
the caller**, including `FAILED`, `TIMED_OUT`, `CANCELLED` and the compensation outcomes. It is
evaluated over whatever data exists, so callers always receive partial results. Fallback values
are flagged in `steps[].fallbackApplied` and in `errors[]`, so they can't be mistaken for real
data.

**Out of scope:** compensation (US-5.6) and callback timeouts (US-6.4, which reuses these
policies).

## Acceptance criteria
- [ ] **AC1 (FAIL_RUN):** Given api3 fails with `500` after retries under `FAIL_RUN`, api4 runs in
  parallel, and api5 is pending, when the failure is processed, then:
  - `stop_reason=FAIL_RUN` is committed with api3's `FAILED`;
  - api5 becomes `CANCELLED`;
  - api4 is allowed to finish and its real outcome is recorded;
  - the run ends `FAILED` with `error_code = STEP_FAILED`, `error_step_id = api3`, and the event
    `RUN_FAILED`.

  `errors[]` contains `{stepId:"api3", code:"HTTP_ERROR", httpStatus:500}` and
  `{stepId:"api5", code:"CANCELLED"}`.
- [ ] **AC2 (stop survives a crash):** Given the node crashes right after committing api3's
  `FAILED` with `stop_reason`, and before cancelling api5, when another node recovers the run
  (US-5.5), then no new step is started, api5 becomes `CANCELLED`, and the run ends `FAILED`.
- [ ] **AC3 (CONTINUE):** Given api5 (notification) fails under `CONTINUE`, when the run finishes,
  then api5 is `FAILED`, the other steps are `SUCCEEDED`, and the run is `COMPLETED_WITH_ERRORS`
  with `errors[]` describing api5 and the event `RUN_COMPLETED_WITH_ERRORS`.
- [ ] **AC4 (FALLBACK):** Given api3 (score) with `"onFailure": "FALLBACK"` and `"fallback": "{
  'score': 0, 'source': 'fallback', 'reason': error.code }"`, when api3 times out, then api3 is
  `SUCCEEDED` with `fallback_applied = 1`, api4 receives `score = 0`, and the run ends
  `COMPLETED_WITH_ERRORS`. The event `STEP_FALLBACK_APPLIED` `{errorCode: "TIMEOUT"}` is
  recorded, and `errors[]` contains `{stepId:"api3", code:"TIMEOUT", fallbackApplied:true}`.
  Given a `fallback` expression that itself fails, the step is `FAILED` with `EXPRESSION_ERROR`,
  and the run behaves as `FAIL_RUN`.
- [ ] **AC5 (output):** Given `"output": "{ 'customerId': steps.api1.body.id, 'kyc':
  steps.api2.body.status }"`, when the run completes, then `run_context(output)` holds the
  evaluated object and the run resource returns it. Given `output` omitted, it is `{ "api1": {…},
  "api2": {…} }` for the steps that succeeded.
- [ ] **AC6 (partial output on failure):** Given the AC1 run (FAILED at api3), when it is
  returned to a sync caller, then `output` is evaluated over the available data, for example
  `{"customerId":"C-77","kyc":"VERIFIED"}` with no score. `outputFinal` is `true`, and
  `X-Apiorch-Run-Status: FAILED` is set.
- [ ] **AC7:** Given an `output` expression that fails at runtime, when the run finishes, then the
  status is `COMPLETED_WITH_ERRORS` (or stays `FAILED` if already failed), `output` is `null`,
  and `errors[]` contains `{code:"OUTPUT_EXPRESSION_ERROR"}`.
- [ ] **AC8:** Given all steps `SUCCEEDED` or `SKIPPED(CONDITION_FALSE)`, when the run finishes,
  then the status is `COMPLETED`, the event `RUN_COMPLETED` `{durationMs, steps}` is recorded,
  and `completed_at` and `ended_at` are set.

## Technical details
**Components**
- `engine.FailurePolicyApplier.apply(run, step, failure)` returns a `PolicyOutcome`:
  `STOP_RUN`, `CONTINUE` or `FALLBACK_APPLIED(body)`. It is pure, and reused by the dry run
  (US-3.5).
- With `STOP_RUN`, the same fenced transaction that marks the step `FAILED` sets
  `pipeline_run.stop_reason='FAIL_RUN'`, `error_code`, `error_step_id` and `error_message`.
  `advance` reads `stop_reason` on every call; when it is set, `advance` schedules nothing and
  cancels pending steps. Once nothing is running, it finalizes.
- `engine.RunFinalizer.finalize(run)` runs in one fenced transaction. It does the following:
  1. Computes the final status (README §7).
  2. Evaluates `output`.
  3. Sets `completed_at` and `ended_at`.
  4. Writes the final event and the outbox rows (US-6.5).
  5. Releases the lease (`owner_instance=NULL, lease_until=NULL`).

  `afterCommit` then completes the sync waiters (US-4.1), logs `run finished`, and releases
  admission (US-5.4).

**Error model in the run resource:**
`errors[]: [{ "stepId", "code", "message", "httpStatus", "fallbackApplied" }]`, with codes from
the attempt outcomes (README §10) plus `CANCELLED`, `ENDPOINT_DISABLED`, `EXPRESSION_ERROR`,
`CALLBACK_TIMED_OUT`, `CALLBACK_REPORTED_FAILURE`, `STEP_OUTCOME_UNKNOWN`, `RUN_TIMEOUT` and
`OUTPUT_EXPRESSION_ERROR`.

**Fallback context:** the run context (ancestors) plus `error = {code, message, httpStatus}`.

**Data:** `pipeline_run.stop_reason`, `error_code`, `error_message` and `error_step_id`;
`step_execution.fallback_applied`, `error_code` and `error_message`.

**Logging:** WARN `step failed policy=FAIL_RUN code=HTTP_ERROR httpStatus=500`; INFO `fallback
applied`; INFO `run finished status=…`, logged after commit.

**Test notes**
- `FailurePolicyApplierTest` covers the three policies, a fallback expression error, and the
  `error` binding.
- `FailRunIT` covers AC1 and AC6.
- `FailRunCrashIT` uses two contexts and kills node A after the `FAILED` commit (AC2).
- `ContinuePolicyIT` covers AC3.
- `FallbackPolicyIT` covers AC4.
- `OutputAssemblyIT` covers AC5, AC7 and AC8.
