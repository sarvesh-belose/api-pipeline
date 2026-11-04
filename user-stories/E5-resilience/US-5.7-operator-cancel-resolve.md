# US-5.7 Operator cancel of a run and resolution of unknown steps
**Epic:** E5 Resilience · **Priority:** Must · **Size:** M · **Depends on:** US-4.6, US-5.5 · **Release:** R1

## User story
As an **Operations Engineer**, I want to cancel a run that should not continue, and to record
the real outcome of a step whose result is unknown, so that from the first release I can
contain bad transactions and repair runs that a crash or deadline left uncertain, without ever
re-sending a call that may already have taken effect.

## Description
There are two operator actions, both requiring `apiorch.ops` and an audit `reason`.

1. **Cancel** works on any run that isn't terminal (`ACCEPTED`, `RUNNING`, `WAITING_CALLBACK`,
   `COMPLETED_PENDING_ASYNC`). It persists `stop_reason = CANCEL` and sets `wakeup_requested`,
   so the driving node, or a sweeper if none, acts on it (README §5.1). Then:
   - no new step starts;
   - pending steps become `CANCELLED`, and so do awaiting-callback steps, unless they are
     eligible for compensation;
   - in-flight steps finish and keep their **real** outcome, so a step that really succeeded is
     `SUCCEEDED` and can be compensated;
   - with `compensate=true` and eligible steps, the run goes to `COMPENSATING` (US-5.6),
     otherwise `CANCELLED`.

   A `COMPENSATING` run can't be cancelled.
2. **Resolve** applies to a step in `UNKNOWN`. The operator records `SUCCEEDED`, with an
   optional body and HTTP status, or `FAILED`, after checking the downstream system. Resolving
   doesn't advance the run; the operator then resumes it (US-5.8, R2) or cancels it.

**Out of scope:** resume (US-5.8) and editing inputs.

## Acceptance criteria
- [ ] **AC1:** Given a `RUNNING` run with api3 in flight and api4 pending, when an operator sends
  `POST /api/v1/runs/{id}/cancel` with `{"reason":"Customer withdrew","compensate":false}`, then
  the event `RUN_CANCEL_REQUESTED` `{actor, reason}` is recorded, no new step starts, and api4
  becomes `CANCELLED`. Once api3 finishes, its real outcome is recorded, for example
  `SUCCEEDED`. The run ends `CANCELLED` (`RUN_CANCELLED`), and the response is `202` with the
  run resource.
- [ ] **AC2:** Given cancel with `"compensate": true`, and api1 plus the in-flight api3 that then
  succeeds both have `compensate`, when processed, then the run goes to `COMPENSATING`, api3
  and then api1 are compensated (US-5.6), and the run ends `COMPENSATED`. Before US-5.6 is
  delivered, `compensate=true` returns `400` `validation-failed` ("compensation not available").
- [ ] **AC3:** Given a run in `WAITING_CALLBACK` or `COMPLETED_PENDING_ASYNC` that no node is
  driving, when cancelled, then the cancel is applied immediately by the receiving node, which
  claims the run through the wake-up. A callback arriving afterwards is recorded as `LATE`
  (US-6.2) and changes nothing.
- [ ] **AC4:** Given a run in `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, `TIMED_OUT`,
  `COMPENSATED`, `COMPENSATION_FAILED`, `CANCELLED` or `COMPENSATING`, when cancel is requested,
  then the response is `409` `illegal-run-state` with the current status.
- [ ] **AC5:** Given a run `FAILED` because api2 is `UNKNOWN` (US-5.5 or US-5.1), when an operator
  sends `POST /api/v1/runs/{id}/steps/api2/resolve` with `{"outcome":"SUCCEEDED","body":{"id":"K-9"},
  "httpStatus":201,"reason":"Confirmed in KYC console"}`, then api2 becomes `SUCCEEDED` with
  `steps.api2.body` stored, and the event `STEP_RESOLVED` `{actor, reason, outcome}` is recorded.
  The run stays `FAILED` until it is resumed (US-5.8) or cancelled.
- [ ] **AC6:** Given resolve on a step that is not `UNKNOWN`, the response is `409`
  `illegal-run-state`. Given any operator action without `reason`, it is `400`.
- [ ] **AC7:** Given any action, then the actor (JWT principal) and reason are stored in the
  event's `actor` and `details`, and the caller webhook (US-6.5, R2) fires for the resulting
  terminal status.

## Technical details
**API contract**
- `POST /api/v1/runs/{runId}/cancel` takes `{"reason": "1–500 chars", "compensate": false}` and
  returns `202` with the run resource. Cancellation completes asynchronously.
- `POST /api/v1/runs/{runId}/steps/{stepId}/resolve` takes `{"outcome": "SUCCEEDED|FAILED",
  "body": {…}?, "httpStatus": 200?, "reason": "…"}` and returns `200` with the step view.

**Components** (`com.crisil.apiorch.engine`)
- `RunOperatorService.cancel` takes the run row lock (README §5.1 rule 1) and checks the status.
  It sets `stop_reason='CANCEL'`, `compensate_on_stop` and `wakeup_requested=true`, then records
  the event and commits. `afterCommit` calls `RunCoordinator.tryClaimAndAdvance(runId)`, which
  succeeds when no node is driving the run. Otherwise the driver sees `wakeup_requested` on its
  next fenced transaction. `advance` treats `stop_reason=CANCEL` like the US-4.6 stop: pending
  steps are cancelled, and the run finalizes when nothing is in flight.
- `RunOperatorService.resolve` takes the run lock, then the step lock, writes
  `run_context(<stepId>)` and the step state, and records `STEP_RESOLVED`. It doesn't wake the
  run.
- These endpoints require `ROLE_apiorch.ops` (US-1.2) and are not bound to `created_by`.

**Data:** `pipeline_run.stop_reason`, `compensate_on_stop` and `wakeup_requested`;
`run_event.actor` and `details`.

**Logging:** WARN `operator action=CANCEL actor=… reason=…`.

**Test notes**
- `CancelIT` covers a running run where the in-flight step finishes `SUCCEEDED` and the pending
  step is cancelled (AC1).
- `CancelCompensateIT` covers AC2 and is tagged `pending-R3` until US-5.6.
- `CancelWaitingIT` covers a waiting run and a late callback (AC3).
- `CancelIllegalStateIT` covers AC4.
- `ResolveUnknownIT` uses the US-5.5 crash fixture (AC5, AC6).
- `OperatorAuditIT` covers AC7.
