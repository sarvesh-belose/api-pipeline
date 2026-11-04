# US-5.5 Recover runs after a node crash; graceful shutdown
**Epic:** E5 Resilience · **Priority:** Must · **Size:** L · **Depends on:** US-4.4 · **Release:** R1

## User story
As an **Operations Engineer**, I want runs to survive the loss of the node executing them,
with a guarantee that two nodes never drive the same run, and deployments to hand runs over
cleanly, so that a pod crash, GC pause or rolling restart never leaves a business transaction
stuck, duplicated or silently half-done.

## Description
This story implements the lease and fencing rules of README §5.1. All run and step state is
persisted before it is acted on (E4).
- **Lease.** A node drives a run only while it holds the lease (`owner_instance`, `lease_epoch`,
  `lease_until` in database time). It renews **every run it owns, whatever the status**,
  including `COMPLETED_PENDING_ASYNC` runs executing post-completion work.
- **Fencing.** Every write the driving node makes is checked against its `(owner, epoch)`. If the
  lease was lost during a GC pause, a database outage or a slow attempt, the write aborts with
  `LeaseLostException`. The node then drops the run locally and discards any late results.
- **Recovery.** A cluster-safe `RecoverySweeper` claims runs matching the single claim
  predicate: an expired lease, or no owner with `wakeup_requested`. It resumes them from
  persisted state.
  - Steps found `RUNNING` are re-executed if `idempotent`, with the same outbound key.
  - Otherwise they become `UNKNOWN` and the run fails for an operator decision (US-5.7).
  - A persisted `stop_reason` is honoured, so a stopping run never starts new steps.
- **Graceful shutdown.** On shutdown the node does the following:
  1. stops admitting new runs (`503` `shutting-down`);
  2. starts no new steps;
  3. lets in-flight attempts finish within the grace period;
  4. releases its leases with `wakeup_requested = 1`, so other nodes take over at once.

  Attempts still running after the grace period are handled by fencing: their late results are
  discarded, and recovery applies the idempotency rule.

**Out of scope:** exactly-once side effects. Delivery is at-least-once for idempotent steps, and
`UNKNOWN` is surfaced for the others.

## Acceptance criteria
- [ ] **AC1:** Given node A drives a run at step api3 of 5 and A stops abruptly, when its lease
  expires, then node B claims the run within `apiorch.lease.duration-ms +
  apiorch.recovery.sweep-interval-ms`. B records `RUN_RECOVERED` `{previousOwner, newOwner,
  epoch}` and continues from api3. api1 and api2 are not re-called.
- [ ] **AC2:** Given the interrupted api3 is a GET (idempotent), when recovered, then it is
  re-executed with the same `Idempotency-Key: {runId}:api3:{execNo}`. Given a POST with
  `idempotent: false`, it becomes `UNKNOWN` (`STEP_OUTCOME_UNKNOWN`), the run becomes `FAILED`
  with `error_code = STEP_OUTCOME_UNKNOWN`, and nothing is re-sent.
- [ ] **AC3 (fencing):** Given node A is paused (simulated by freezing its renewal) until B has
  claimed the run (epoch 2), when A's in-flight attempt completes, then A's attempt to record the
  result finds no row for `(A, epoch 1)`. A writes **nothing** (no `step_execution`, `run_event`
  or `run_context` change), drops the run locally, and logs WARN `lease lost`. Only B's
  execution is persisted.
- [ ] **AC4:** Given two nodes' sweepers run at the same moment, when both try to claim the same
  run, then exactly one succeeds, and the run is driven once.
- [ ] **AC5:** Given a live node driving a 2-minute run with a 30 s lease, including a
  `COMPLETED_PENDING_ASYNC` run executing post-completion steps, then its lease is renewed every
  `renew-interval-ms`, and no other node claims it. Given renewal fails for one run (for example
  the row was claimed elsewhere), that run is dropped locally at the next renewal.
- [ ] **AC6:** Given a run created and committed whose coordinator hand-off failed, or whose node
  died right after commit, then it is recovered by the sweeper within one lease duration. It
  never stays `ACCEPTED` forever, because it was created owned (README §5.1 rule 6).
- [ ] **AC7:** Given SIGTERM on node A with 3 runs in flight, when shutdown begins, then:
  - readiness turns `DOWN`, and new run requests get `503` `shutting-down`;
  - no new steps start, and in-flight attempts get up to 20 s;
  - leases are released with `wakeup_requested = 1`.

  Node B resumes those runs within one sweep interval, and no run is lost or driven twice.
- [ ] **AC8:** Given a sync caller whose run was driven by a crashed node, when it retries with
  the same `Idempotency-Key` on node B (US-4.7), then it receives the recovered run's result.

## Technical details
**Components** (`com.crisil.apiorch.engine`)
- `RunLeaseService`, with all SQL through `JdbcClient` and `SYSUTCDATETIME()` from the
  database (ADR-003 rule 1):
  - `claim(runId)`:
    ```sql
    UPDATE pipeline_run SET owner_instance=:me, lease_epoch=lease_epoch+1,
      lease_until=DATEADD(MILLISECOND, :leaseMs, SYSUTCDATETIME()), wakeup_requested=0
    WHERE id=:id AND ((owner_instance IS NULL AND wakeup_requested = 1)
      OR (owner_instance IS NOT NULL AND lease_until < SYSUTCDATETIME()))
    ```
    One row updated means the claim succeeded; the new epoch is read back.
  - `lockFenced(runId, epoch)` runs the dialect row lock `SqlDialect.lockRunSql()`, selecting
    `WHERE id=:id AND owner_instance=:me AND lease_epoch=:epoch`: SQL Server
    `SELECT … FROM pipeline_run WITH (UPDLOCK, ROWLOCK) WHERE …`, H2 `SELECT … WHERE … FOR UPDATE`
    (ADR-003 F3, F4, rule 4). It throws `LeaseLostException` on zero rows. **Every** coordinator
    write transaction starts with it (README §5.1 rules 1 and 3).
  - The lock wait is bounded: the statement is wrapped in `SqlDialect.withLockTimeout(sql,
    apiorch.engine.lock-timeout-ms)`, which on SQL Server prefixes `SET LOCK_TIMEOUT n;` in the
    same batch, because a separate `SET` is reverted by `sp_executesql` (ADR-003 F5). On H2 the
    timeout is set per session. A timeout (SQL Server 1222, H2 50200) aborts the write transaction.
  - `renewAll()` is a `@Scheduled` task every `renew-interval-ms`. It runs `UPDATE … SET
    lease_until = DATEADD(MILLISECOND, :leaseMs, SYSUTCDATETIME()) WHERE owner_instance=:me`,
    then selects the ids it still owns. Any locally driven run missing from that set triggers
    `onLeaseLost`.
  - `:leaseMs` is `apiorch.lease.duration-ms`, bound as a long. `LeaseSqlIT` runs every
    statement in this class on both tiers (H2 fast tier and Testcontainers SQL Server, `slow`),
    so a syntax slip fails the build rather than the first recovery in production.
  - `release(runId, wakeup)` sets `owner_instance=NULL, lease_until=NULL,
    wakeup_requested=:wakeup` (`BIT`, bound as 0 or 1).
- `RecoverySweeper` runs every `apiorch.recovery.sweep-interval-ms`. Every node runs it, which
  is safe because claims are atomic. It selects up to 50 candidates matching the claim
  predicate (`SELECT TOP (50) …`), ordered by `lease_until` (ascending order puts NULLs
  first on both databases), claims each one, and calls
  `RunRecovery.resume(run)`. That call does the following:
  1. If `deadline_at` has passed and the run is in a normal segment, finalize per US-5.1 AC7.
     Wake-up claims start a new segment first (US-6.3).
  2. Reconcile steps found `RUNNING`: idempotent ones go to `PENDING`, others to `UNKNOWN`.
  3. Honour `stop_reason`.
  4. Continue compensation for `COMPENSATING` runs.
  5. Otherwise call `RunCoordinator.advance`.
- `GracefulShutdownCoordinator` is a `SmartLifecycle` with a phase above the web server's.
  Its `stop()` publishes `REFUSING_TRAFFIC`, sets `RunService.draining = true` (new starts get
  `503`), sets the coordinator to `draining`, awaits in-flight attempts up to 20 s, then calls
  `releaseAllOwned(wakeup=true)`.
- **Instance id:** `apiorch.instance-id`, written to `owner_instance` and `run_event.instance_id`.

**Data:** `pipeline_run.owner_instance`, `lease_epoch`, `lease_until`, `wakeup_requested` and
`stop_reason`, with the indexes from README §10; `step_execution.status = 'UNKNOWN'`.

**Configuration:** `apiorch.lease.duration-ms` (30000), `apiorch.lease.renew-interval-ms`
(10000), `apiorch.recovery.sweep-interval-ms` (15000), `apiorch.engine.lock-timeout-ms` (2000,
ADR-003 F5), `apiorch.instance-id`, and
`spring.lifecycle.timeout-per-shutdown-phase` (30s).

**Logging:** WARN `run recovered previousOwner=… epoch=…`; WARN `lease lost runId=… epoch=…`;
ERROR `step outcome unknown – manual resolution required`; INFO `shutdown: released N leases`.

**Metrics:** `apiorch.recovery.runs.recovered`, `apiorch.recovery.steps.unknown` and
`apiorch.lease.lost`.

**Test notes** (ADR-003 rule 6: the lock, lease and concurrency tests below are `slow`-tagged and
run on Testcontainers SQL Server, because H2 ignores the SQL Server lock hint (F3); `LeaseSqlIT`
also runs in the H2 fast tier)
- `CrashRecoveryIT` uses two contexts. A is stopped abruptly, with renewal and release disabled
  (AC1, AC2).
- `FencingIT` freezes A's renewal bean, lets B claim the run, completes A's WireMock response,
  and asserts that no A writes exist (AC3).
- `ConcurrentSweepIT` covers AC4.
- `LeaseRenewalIT` covers a long run and a pending-async run with post-completion work (AC5).
- `OrphanAcceptedIT` covers AC6.
- `GracefulShutdownIT` covers AC7.
- `RecoveryIdempotentReplayIT` covers AC8.
