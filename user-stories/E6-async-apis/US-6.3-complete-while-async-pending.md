# US-6.3 Mark a run complete while async steps are pending; resume dependents on callback
**Epic:** E6 Async steps · **Priority:** Must · **Size:** L · **Depends on:** US-6.2 · **Release:** R2

## User story
As a **Calling System**, I want my run to be reported complete as soon as all the work I need is
done, even if an optional async step hasn't called back yet, and I want apiorch to finish the
remaining work reliably when that callback arrives, so that I'm not blocked by slow background
processes and nothing is lost.

## Description
At quiescence (nothing running, nothing ready), the coordinator looks at what remains. It does
this **inside its fenced, run-locked transaction**, after re-reading the step states and
`wakeup_requested` (README §5.1):

| Remaining work | Run becomes |
|---|---|
| `wakeup_requested` is set | Clear it and keep advancing. A callback or timeout landed meanwhile |
| Nothing: all steps terminal | Final status (US-4.6) |
| Only steps awaiting callback where **any** has `blocksRunCompletion: true`, plus their pending dependents | `WAITING_CALLBACK` (not done for the caller) |
| Only non-blocking async steps and their **post-completion** dependents | **`COMPLETED_PENDING_ASYNC`** (done for the caller; `outputFinal: false`) |

In both waiting states, the lease is released (`owner_instance = NULL`, `lease_until = NULL`).

A callback (US-6.2) or timeout (US-6.4) sets `wakeup_requested`. The receiving node then
claims the run immediately. If that fails, the `RecoverySweeper` (US-5.5) claims it. A
claim that comes from a wake-up **starts a new segment** before any deadline check (US-5.1).
Dependents then run:
- From `WAITING_CALLBACK`, the run goes back to `RUNNING`.
- From `COMPLETED_PENDING_ASYNC`, the status stays put, and the run is driven under a renewed
  lease (US-5.5).

When everything is terminal, the run gets its final status and output, with
`outputFinal: true`. Post-completion failures and deadlines always end in
`COMPLETED_WITH_ERRORS`, never `FAILED` (README §7, enforced by US-3.2).

**Out of scope:** callback timeouts (US-6.4) and caller webhook delivery (US-6.5, which this
story triggers).

## Acceptance criteria
- [ ] **AC1 (headline):** Given pipeline `api1 → api2 → {api3, api4}`, where api4 is async with
  `blocksRunCompletion: false` and `onFailure: CONTINUE`, and api5 (`onFailure: CONTINUE`)
  depends on api4, when a sync run executes and api4 has been acknowledged but not called back,
  then:
  - the caller receives `200` with `"status": "COMPLETED_PENDING_ASYNC"`,
    `"pendingAsyncSteps": ["api4"]`, `"outputFinal": false` and the output computed from the
    available data;
  - `RUN_COMPLETED_PENDING_ASYNC` is recorded;
  - `completed_at` is set and `ended_at` is null.
- [ ] **AC2:** Given that run, when api4's callback arrives (WireMock webhook after 2 s), then a
  new segment starts, api5 executes, and the status stays `COMPLETED_PENDING_ASYNC` while api5
  runs. The run then becomes `COMPLETED` with `ended_at` set, `output` recomputed with api4 and
  api5 data, `outputFinal: true`, `pendingAsyncSteps: []`, and `RUN_COMPLETED`.
- [ ] **AC3 (late callback):** Given `runTimeoutMs: 30000` and api4's callback arriving 2 minutes
  after the first segment ended, when the run is woken, then api5 still executes, and the run
  completes. The stale `deadline_at` never times it out (US-5.1 AC5).
- [ ] **AC4 (blocking):** Given api4 with `blocksRunCompletion: true` (default), when only api4 is
  outstanding, then the run is `WAITING_CALLBACK` (`RUN_WAITING_CALLBACK`). A sync caller still
  waiting gets `202` when `syncWaitMs` elapses. After the callback the run goes `RUNNING →
  COMPLETED`.
- [ ] **AC5:** Given a waiting run, then `owner_instance` and `lease_until` are NULL. After a
  callback is accepted, `wakeup_requested` is true, the receiving node claims the run (new
  epoch), and api5 starts within 1 s.
- [ ] **AC6 (lost wake-up):** Given the node that accepted the callback dies after commit and
  before claiming, when `apiorch.recovery.sweep-interval-ms` elapses, then another node's
  sweeper claims the run through `wakeup_requested` and completes it.
- [ ] **AC7:** Given a callback accepted on node B while node A is still driving the run (other
  parallel steps in flight), then B's claim fails because A's lease is valid. A sees
  `wakeup_requested` and api4 `SUCCEEDED` on its next fenced transaction, then schedules api5.
  No step runs twice.
- [ ] **AC8:** Given a post-completion step that fails (callback reports failure, or a timeout
  under US-6.4) under `CONTINUE`, when the run finishes, then the status is
  `COMPLETED_WITH_ERRORS`, with `errors[]` for that step and its skipped dependents.

## Technical details
**Components** (`com.crisil.apiorch.engine`)
- `RunCoordinator.advance`, at quiescence, calls `QuiescenceEvaluator.evaluate(steps, run)`
  **within the same fenced transaction** that holds the run row lock. It returns `CONTINUE`
  (wake-up flag set), `FINAL`, `WAIT_BLOCKING` or `PENDING_ASYNC`:
  - `WAIT_BLOCKING`: status `WAITING_CALLBACK`, release (`owner_instance=NULL,
    lease_until=NULL`), commit.
  - `PENDING_ASYNC`: on the first entry only, status `COMPLETED_PENDING_ASYNC`, `completed_at`,
    evaluate `output`, and write the outbox event `run.pending_async` (US-6.5); then release and
    commit. `afterCommit` notifies the sync waiters.
- `RunCoordinator.tryClaimAndAdvance(runId)` is called after a callback, timeout or operator
  commit. It runs `RunLeaseService.claim`, which uses the single predicate (README §5.1). On
  success it calls `startSegment` (US-5.1), moves `WAITING_CALLBACK` to `RUNNING`, and calls
  `advance`.
- `RecoverySweeper` (US-5.5) picks up any run with `owner_instance IS NULL AND
  wakeup_requested` (AC6).
- The post-completion subgraph is precomputed in `CompiledPipeline` (US-3.3).
  `RunFinalizer` uses it to apply `COMPLETED_WITH_ERRORS` instead of `TIMED_OUT` (US-5.1).

**Run resource:** `pendingAsyncSteps` lists the steps in `AWAITING_CALLBACK`. `outputFinal` is
`false` while the run is `COMPLETED_PENDING_ASYNC`. `completedAt` is when the run became done
for the caller, and `endedAt` is when it became terminal.

**Data:** `pipeline_run.status`, `completed_at`, `ended_at`, `owner_instance`, `lease_epoch`,
`lease_until`, `wakeup_requested`, `segment_started_at` and `deadline_at`.

**Logging:** INFO `run completed pending async pending=[api4]`; INFO `run woken by callback
stepId=api4 epoch=…`.

**Test notes**
- `CompletePendingAsyncIT` (headline) uses a WireMock 202 ack and a webhook listener posting
  `{{originalRequest.headers.X-Callback-Url}}` after 2 s (AC1, AC2, AC8).
- `LateCallbackNewSegmentIT` sets a short `runTimeoutMs` and a long callback delay (AC3).
- `WaitingCallbackIT` covers AC4 and AC5.
- `LostWakeUpIT` uses two contexts, with node A's `tryClaimAndAdvance` disabled by a test hook
  (AC6).
- `CallbackDuringRunningIT` asserts WireMock counts (AC7).
