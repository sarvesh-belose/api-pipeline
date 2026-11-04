# US-5.1 Enforce step timeouts and the run deadline
**Epic:** E5 Resilience · **Priority:** Must · **Size:** M · **Depends on:** US-4.3 · **Release:** R1

## User story
As an **Operations Engineer**, I want every downstream call bounded by a timeout and every
active stretch of a run bounded by a deadline, without ever re-sending a call whose outcome is
unknown, so that one hanging API on another infrastructure can't block a run, a thread or a
caller indefinitely or cause duplicate side effects.

## Description
There are three time limits:
1. **Connect timeout**, per endpoint (US-2.1).
2. **Attempt timeout:** the effective step `timeoutMs` (step, then `defaults`, then the endpoint
   `readTimeoutMs` resolved at attempt time), **capped by the remaining segment budget**
   (`deadline_at − now`).
3. **Segment deadline:** `runTimeoutMs` bounds each **segment** (README §6). A new segment starts:
   - at run start;
   - when a waiting run is woken by a callback or a callback timeout (US-6.3/6.4), **before** any
     deadline check;
   - on operator resume (US-5.8);
   - when compensation begins (US-5.6), using `apiorch.compensation.deadline-ms`.

   Time spent in `WAITING_CALLBACK` or `COMPLETED_PENDING_ASYNC` without activity belongs to no
   segment.

**Abort mechanics.** In-flight attempts are never interrupted. Their timeouts are already capped
by the deadline, so they end at the deadline themselves. An attempt whose timeout was capped by
the budget and then fires is classified **`DEADLINE_EXCEEDED`**, not step `TIMEOUT`. Its step
then becomes:
- **`CANCELLED`** if the step is idempotent, because it is safe to send again;
- **`UNKNOWN`** if it is not idempotent and the request was sent. It is never re-sent
  automatically (US-5.7).

When a segment's deadline passes:
- **Normal segment:** pending steps become `CANCELLED`, and the run becomes `TIMED_OUT`, or
  `COMPENSATING` (US-5.6).
- **Post-completion segment:** the run becomes `COMPLETED_WITH_ERRORS` with error `RUN_TIMEOUT`.
  It never becomes `TIMED_OUT` (README §7).

**Out of scope:** retry budget arithmetic (US-5.2) and callback timeouts (US-6.4).

## Acceptance criteria
- [ ] **AC1:** Given api2 with `timeoutMs: 2000`, a WireMock delay of 5 s, and plenty of run
  budget, when api2 runs, then the attempt ends with outcome `TIMEOUT`. Its `duration_ms` is at
  least 2000 and under 4000, and the retry and policy rules apply.
- [ ] **AC2:** Given an endpoint that doesn't accept connections within `connectTimeoutMs`, when
  called, then the attempt ends with outcome `CONNECT_ERROR`. A connect timeout is never
  classified as `TIMEOUT`.
- [ ] **AC3:** Given `runTimeoutMs: 3000`, steps of 1 s and 1 s, then api3 (GET, idempotent) with a
  5 s delay, when the run executes, then api3's attempt timeout is capped at about 1 s. It ends
  with outcome `DEADLINE_EXCEEDED`; api3 becomes `CANCELLED` and the later steps `CANCELLED`. The
  run becomes `TIMED_OUT` with `error_code = RUN_TIMEOUT`, and the event `RUN_TIMED_OUT`
  `{deadlineAt, aborted:["api3"]}` is recorded. The outcome is deterministic: there is never a
  race between a step `TIMEOUT` and the run deadline.
- [ ] **AC4:** Given the same run where api3 is a `POST` with `idempotent: false`, when the
  deadline cuts its attempt, then api3 becomes `UNKNOWN` (event `STEP_OUTCOME_UNKNOWN`), and the
  run becomes `TIMED_OUT`. A later resume is refused until api3 is resolved (US-5.8).
- [ ] **AC5:** Given a run in `COMPLETED_PENDING_ASYNC` with `runTimeoutMs: 30000`, whose async
  step's callback arrives 2 hours later, when the run is woken, then a **new segment** starts
  (`segment_started_at`, new `deadline_at`) before any deadline check, and the dependents
  execute. If those dependents exceed the new deadline, the run ends `COMPLETED_WITH_ERRORS`
  with `errors[]` `{code:"RUN_TIMEOUT"}`, not `TIMED_OUT`.
- [ ] **AC6:** Given a sync caller whose run times out within `syncWaitMs`, then the caller
  receives `200` with `status: "TIMED_OUT"` and the partial output (US-4.6).
- [ ] **AC7:** Given the driving node dies during a `RUNNING` segment, when another node recovers
  the run after `deadline_at` (US-5.5), then the run is finalized as `TIMED_OUT`, applying AC3
  and AC4 to steps found `RUNNING`, instead of resuming them. Recovery never starts a new
  segment; only a wake-up from a waiting status, a resume or compensation does.

## Technical details
**Effective attempt timeout** (computed in `ResilientHttpClient` before each attempt):
`budget = deadline_at − dbNow`, `stepTimeout = step.timeoutMs ?? defaults.timeoutMs ??
endpoint.readTimeoutMs`, `attemptTimeout = min(stepTimeout, budget)`, `cappedByBudget = budget <
stepTimeout`. If `budget ≤ 100 ms`, the attempt is not started and gives `DEADLINE_EXCEEDED`.

**Per-attempt timeout mechanics:** `RestClient` has no per-request timeout API.
- `EndpointRuntime` keeps a small cache of `JdkClientHttpRequestFactory` instances, keyed by a
  **timeout bucket**: the attempt timeout rounded up to 250 ms, capped at 120 s. Each factory is
  created with `setReadTimeout(bucket)` and shares the endpoint's `HttpClient`.
- `RestClient` instances per bucket come from `endpointRestClient.mutate().requestFactory(f)`,
  so interceptors (auth, capture) and observations are preserved.
- Because the bucket rounds up, the effective timeout may exceed the computed value by up to
  250 ms. That stays within the AC tolerances.

**Exception mapping:** unwrap `ResourceAccessException`, then test
`HttpConnectTimeoutException` / `ConnectException` / `UnresolvedAddressException` **first**
(`CONNECT_ERROR`), then `HttpTimeoutException` (`TIMEOUT`, or `DEADLINE_EXCEEDED` when
`cappedByBudget`), then other `IOException` (`IO_ERROR`).

**Components** (`com.crisil.apiorch.engine`)
- `DeadlineScheduler` runs a `ScheduledExecutorService` named `apiorch-deadline`, scheduled at
  `deadline_at` when a segment starts. When it fires, in a fenced transaction it sets
  `stop_reason = DEADLINE` (README §5.1). Pending steps become `CANCELLED`. In-flight attempts
  end themselves within at most 250 ms, and their completion finalizes the run. The scheduled
  task is cancelled when the segment ends.
- `RunCoordinator.startSegment(run)` sets `segment_started_at` and `deadline_at = dbNow +
  runTimeoutMs` (or `apiorch.compensation.deadline-ms` for compensation). It is called by start,
  `tryClaimAndAdvance` after a wake-up (US-6.3), resume (US-5.8) and `CompensationRunner`
  (US-5.6).
- `RunFinalizer` chooses `TIMED_OUT` for normal segments and `COMPLETED_WITH_ERRORS` (with
  `RUN_TIMEOUT`) for post-completion segments.

**Data:** `pipeline_run.deadline_at`, `segment_started_at`, `stop_reason = 'DEADLINE'` and
`error_code = 'RUN_TIMEOUT'`; `step_execution.status` `CANCELLED` or `UNKNOWN`; attempt outcome
`DEADLINE_EXCEEDED`.

**Configuration:** `apiorch.run.default-run-timeout-ms` (60000), the pipeline `runTimeoutMs`, the
step `timeoutMs`, and `apiorch.compensation.deadline-ms` (300000).

**Logging:** WARN `attempt timed out timeoutMs=2000`; WARN `segment deadline reached aborted=[…]
unknown=[…]`.

**Test notes**
- `StepTimeoutIT` uses generous bounds (AC1).
- `ConnectTimeoutIT` uses a closed local port and a black-hole address, tagged `network` (AC2).
- `RunDeadlineIT` covers idempotent and non-idempotent steps, and the deterministic outcome
  across 20 repetitions (AC3, AC4, AC6).
- `PostCompletionDeadlineIT` uses a WireMock webhook after 3 s, with `runTimeoutMs=1000` and a
  slow dependent (AC5).
- `RecoveredAfterDeadlineIT` covers AC7.
- `TimeoutBucketTest` covers bucket selection and interceptor preservation.
