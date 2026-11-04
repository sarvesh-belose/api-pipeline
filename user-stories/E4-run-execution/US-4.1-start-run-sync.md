# US-4.1 Start a run and get the combined result synchronously
**Epic:** E4 Run execution · **Priority:** Must · **Size:** M · **Depends on:** US-3.3 · **Release:** R1

## User story
As a **Calling System**, I want to make one API call that runs a whole pipeline and returns
the combined result when it finishes, or a run ID to poll if it takes too long, so that I can
integrate many downstream APIs through a single request.

## Description
`POST /api/v1/pipelines/{code}/runs?mode=sync` does the following:
1. Checks the input against the pipeline's `input` spec.
2. Resolves and pins the latest published version (or `?version=N` for admins in non-prod).
3. Creates the run **already owned** by this node, with its step rows, input and `RUN_ACCEPTED`
   event, in one transaction (README §5.1 rule 6).
4. Hands it to the `RunCoordinator`.
5. Waits up to `syncWaitMs` for the run to be **done for the caller** (README §7).

If the run is done in time, the response is `200` with the **run resource**. Otherwise it is
`202` with `Location`, and the run continues. The request thread is released while waiting
(Spring MVC async).

This story defines the run resource shared by US-4.2, US-6.5 and US-7.4, and creates the run
tables. Its ACs are verified with a **test-scope `StubStepExecutor`**, which completes steps
from scripted results without HTTP, so the story doesn't depend on US-4.3.

**Out of scope:** caller-async mode (US-4.2), HTTP step execution (US-4.3), idempotency (US-4.7)
and the caller allow-list (US-4.8).

## Acceptance criteria
- [ ] **AC1:** Given published `CUSTOMER_ONBOARD` v3 and a caller with role `apiorch.run`, when
  it sends `POST /api/v1/pipelines/CUSTOMER_ONBOARD/runs?mode=sync` with a JSON object body and
  the stub completes every step within `syncWaitMs`, then the response is `200` with the run
  resource (`status: "COMPLETED"`, `version: 3`, `output`, `outputFinal: true`, `steps[]`,
  `durationMs`) and the headers `X-Apiorch-Run-Id`, `X-Apiorch-Run-Status: COMPLETED` and
  `X-Correlation-Id`.
- [ ] **AC2:** Given a run still `RUNNING`, `WAITING_CALLBACK` or `COMPENSATING` when
  `syncWaitMs` elapses, when the wait ends, then the response is `202` with
  `Location: /api/v1/runs/{runId}`, `Retry-After: 2`, and the run resource with its current
  status. The run keeps executing.
- [ ] **AC3:** Given a run that ends `FAILED`, `TIMED_OUT` or `COMPLETED_WITH_ERRORS` within the
  wait, when it returns, then the response is `200`, and `X-Apiorch-Run-Status` and the body's
  `status` and `errors[]` describe the outcome (`stepId`, `code`, `message`, `httpStatus`). This
  contract is open question Q4.
- [ ] **AC4:** Given a run that completes in under 5 ms, when the caller waits, then it still
  receives `200`, not `202`. The waiter is registered **before** the run is handed to the
  coordinator, so a fast completion can't be missed.
- [ ] **AC5:** Given the pipeline's `input: {"required":["name","pan"], "validWhen":"$length(pan)
  = 10"}` and a body missing `pan` or with a 9-character `pan`, when submitted, then the response
  is `400` `validation-failed`, listing `missing: pan` or `validWhen failed`, and **no run row**
  is created.
- [ ] **AC6:** Given an unknown code (`404` `pipeline-not-found`), no published version (`404`
  `no-published-version`), a body that isn't a JSON object (`400`), or a body above
  `apiorch.run.max-input-bytes` (`413`), then no run row is created. Given `?version=2` from a
  principal without `apiorch.admin`, or while `apiorch.run.allow-version-override=false`, the
  response is `403` `forbidden`.
- [ ] **AC7:** Given a run is accepted, then **one** transaction inserts `pipeline_run`
  (`ACCEPTED`, pinned version, `created_by`, `created_at`, `correlation_id`, `mode`,
  `owner_instance` = this node, `lease_epoch = 1`, `lease_until` = DB time + lease), one
  `step_execution` row per step (`PENDING`, `exec_no = 1`), `run_context(input)`, and
  `RUN_ACCEPTED` (persisted by US-7.1). If the hand-off to the coordinator fails after commit,
  the run is released with `wakeup_requested = 1`, so recovery (US-5.5) picks it up.
- [ ] **AC8:** Given Tomcat `max-threads=10`, 200 concurrent sync requests and a stub whose
  steps take 2 s, when they are processed, then all 200 receive `200` within 6 s, and the peak
  number of busy Tomcat threads stays at 10 or fewer. If threads were held during the wait,
  these requests would take at least 40 s.

## Technical details
**API contract:** `POST /api/v1/pipelines/{code}/runs?mode=sync|async[&version=N]`
- **Headers in:** `Authorization`, `X-Correlation-Id`, `Idempotency-Key` (US-4.7),
  `X-Callback-Url` (US-6.5) and `Prefer: respond-async` (US-4.2).
- **Body:** a JSON object, which becomes `input`.

**Run resource** (shared with US-4.2, US-6.5 and US-7.4):
```json
{
  "runId": "8d3f0c3e-…", "pipeline": "CUSTOMER_ONBOARD", "version": 3,
  "status": "COMPLETED", "mode": "SYNC", "correlationId": "abc-123",
  "createdAt": "…", "startedAt": "2026-11-04T10:15:02.120Z", "completedAt": "…", "endedAt": "…", "durationMs": 842,
  "output": { "customerId": "C-77", "kyc": "VERIFIED" }, "outputFinal": true, "outputPurged": false,
  "pendingAsyncSteps": [],
  "errors": [],
  "steps": [ { "id": "api1", "status": "SUCCEEDED", "attempts": 1, "httpStatus": 201, "durationMs": 120, "fallbackApplied": false } ],
  "links": { "self": "/api/v1/runs/8d3f0c3e-…", "timeline": "/api/v1/runs/8d3f0c3e-…/timeline" }
}
```
`outputFinal` is `false` while post-completion steps are pending (US-6.3).

**Data:** Flyway `V4__run.sql` creates `pipeline_run`, `step_execution` and `run_context` with
**all** columns and indexes from README §10. That includes `lease_epoch`, `wakeup_requested`,
`stop_reason`, `legal_hold`, `exec_no` and `segment_started_at`.

**Components**
- `run.RunController` returns `CompletableFuture<ResponseEntity<RunResource>>`, and sets
  `X-Apiorch-Run-Status` and `X-Apiorch-Run-Id`.
- `run.RunService.start(code, input, headers, principal)`:
  1. Check that the pipeline is enabled (US-3.7, from R2).
  2. Check that the node isn't draining (`503` `shutting-down`, US-5.5).
  3. Pass admission control (US-5.4, from R2) and the allowed callers check (US-4.8, from R2).
  4. Run the `InputValidator` (required fields, `validWhen`).
  5. Generate `runId`, then `RunCompletionNotifier.register(runId)`, which returns this
     waiter's own future.
  6. Insert the rows and `RUN_ACCEPTED` in one transaction, through `JdbcClient` for
     `pipeline_run` (README §5.1).
  7. `afterCommit` calls `RunCoordinator.start(runId, epoch=1)`. On an exception, it calls
     `RunLeaseService.releaseWithWakeup(runId)`.
- `engine.RunCompletionNotifier` keeps `Map<UUID, List<CompletableFuture<RunView>>>`, one future
  per waiter.
  - The coordinator calls `notifyDone(runId)` from `afterCommit` of the finalizing transaction.
  - Each waiter uses its own `completeOnTimeout(null, wait)`. On completion or timeout, the
    entry is removed.
  - A waiter whose run is driven by another node (recovery, callback wake-up) also polls the
    database, on a **shared** scheduled task rather than one per waiter: one query every 500 ms
    (±20% jitter) fetches the statuses of all locally waiting runs in a single statement, so the
    poll cost is one query per interval per node and not one per concurrent caller. At 500
    concurrent sync callers, a per-waiter poll would be 1,000 queries/s of pure overhead against the
    pool sized in README §14.
  - `null` means "load the current state and answer 202".
  - On completion, timeout or an exception the entry is removed in a `finally`, and a scheduled
    reaper drops entries older than `max-sync-wait-ms + 1 min` as a backstop, so a missed removal
    cannot accumulate futures on a long-lived node.
- `engine.RunCoordinator.start` sets `RUNNING`, `started_at`, `segment_started_at` and
  `deadline_at` (US-5.1) in a fenced transaction, then calls `advance`.
- `run.RunResourceAssembler` builds the resource from `pipeline_run`, `step_execution` and
  `run_context(output)`.

**Configuration:** `apiorch.run.default-sync-wait-ms` (10000), `apiorch.run.max-sync-wait-ms`
(30000), `apiorch.run.max-input-bytes` (1048576), `apiorch.run.allow-version-override` (false;
true in the `local` and `test` profiles), and `spring.mvc.async.request-timeout` (35 s).

**Logging:** INFO `run accepted` with `runId`, `pipeline`, `version`, `mode` and `inputBytes`.
INFO `run responded` with `httpStatus` and `waitedMs`, and MDC restored on async dispatch
(US-1.3).

**Test notes**
- `SyncRunIT` uses `StubStepExecutor`. It covers the 200, 202 and failed cases, the headers,
  fast completion, input checks, error cases, version override and the persisted rows (AC1–AC7).
- `SyncRunThreadUsageIT` covers AC8 with `server.tomcat.threads.max=10`.
- `NotifierLifecycleTest` asserts the map is empty after completion, after timeout and after an
  exception, and that the reaper clears a stale entry.
- `HandOffFailureIT` injects an exception in `RunCoordinator.start` and asserts
  `wakeup_requested` (AC7).
