# US-7.8 Data retention
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-7.2, US-7.6 · **Release:** R2

## User story
As a **Security Officer**, I want every kind of run data purged on a defined schedule, keeping
resumable runs repairable for long enough, so that apiorch keeps exactly as much history as
audits and operations need and no more personal data than necessary.

## Description
A nightly, cluster-safe retention job deletes data in batches:

| Data | Rule (default) |
|---|---|
| `run_context` of runs that are `!active()` and **not** `resumable()` (README §7) | Deleted `context-hours-after-terminal` (24 h) after `ended_at`; sets `context_purged_at` |
| `run_context` of `FAILED` / `TIMED_OUT` runs (resumable, US-5.8) | Deleted `resumable-context-days` (7) after `ended_at`; sets `context_purged_at` |
| Bodies and headers in `step_attempt`, `callback_inbox`, `run_exchange`; `outbox_event.payload` (`SENT`/`DEAD`) | Set to NULL, with `payload_purged=1`, after `payload-days` (30) |
| `run_event`, `step_attempt`, `run_exchange`, `callback_inbox` | A whole **month partition is truncated** and its boundary merged once every row in it is older than `run-days` (180). No row-by-row delete on SQL Server |
| Remaining rows of a run (`pipeline_run`, `step_execution`, `outbox_event`, `run_context`) | Deleted `run-days` (180) after `ended_at`, in batches |
| `endpoint_audit` | Deleted after `run-days` |
| Runs that never reached a terminal status | Never auto-deleted; reported by `apiorch.retention.stuck_runs` |

The periods are defaults pending compliance sign-off (open question Q1).

**Why partitions.** At the NFR load the four partitioned tables take billions of rows (README
§14). Deleting them row by row at 180 days would produce more transaction log and lock
escalation than the write path itself. `TRUNCATE TABLE … WITH (PARTITIONS (n))` is a metadata
operation, so the 180-day rule is expressed as partition maintenance on SQL Server and only the
low-volume tables are deleted by row (ADR-003 F9). The trade-off is **granularity**: a partition is
truncated only when its newest row
is past the cutoff, so run-level history survives up to one month longer than `run-days`. That is
recorded in the retention notice for Q1. Erasure on request (US-7.10) still deletes individual
rows, because it cannot wait for a partition boundary.

**Legal hold** is the one exclusion that is a compliance requirement rather than a nicety: in a
regulated context, a run under investigation or litigation must survive every rule above. The
mechanism is small and is included here, because retrofitting it means auditing every delete path a
second time:
- `pipeline_run.legal_hold` (`BIT`, default 0), set and cleared by `apiorch.ops` with a
  `reason`, recorded as `RUN_HOLD_SET` / `RUN_HOLD_CLEARED`.
- Every retention predicate excludes `legal_hold = 1`, and erasure (US-7.10) refuses with
  `409 run-on-legal-hold`.
- A partition is **not** truncated while it contains any row of a held run; the job reports those
  partitions instead, and an operator moves the held rows out before the truncation. Held runs are
  therefore the one case where storage is not reclaimed on schedule, which is intended.

**Out of scope:** archive export, and erasure on request (US-7.10). Who may place a hold, and on
whose instruction, is open question Q1.

## Acceptance criteria
- [ ] **AC1:** Given a `COMPLETED` run that ended 25 hours ago, when the job runs, then its
  `run_context` rows are deleted and `context_purged_at` is set. `GET /api/v1/runs/{id}` then
  returns `output: null` and `outputPurged: true`. The timeline still works, because it reads
  only metadata and masked logs.
- [ ] **AC2:** Given a `FAILED` run that ended 25 hours ago, when the job runs, then its context
  is **kept** until `resumable-context-days` (7). Resume (US-5.8) still works on day 6, and
  returns `409 context-purged` on day 8.
- [ ] **AC3:** Given attempts, callbacks and exchanges older than 30 days, when the job runs,
  then their header and body columns are NULL with `payload_purged = 1`, while sizes,
  durations and statuses remain. The detail APIs report `payloadPurged: true` (US-7.4, US-7.7).
- [ ] **AC4:** Given runs that ended more than 180 days ago, when the job runs, then each batch
  first **selects a fixed list of run ids** (ordered by `ended_at`, at most 1,000). It then
  deletes those ids' rows from `run_context`, `outbox_event`, `step_execution` and `pipeline_run`,
  in child-to-parent order, in one transaction. No orphan rows remain. This is verified by a query
  of each child table against `pipeline_run` after the job. Rows in the four partitioned tables are
  **not** deleted here; AC7 reclaims them.
- [ ] **AC7 (partition drop):** Given a month partition of `run_event`, `step_attempt`,
  `run_exchange` or `callback_inbox` whose upper bound is older than `SYSUTCDATETIME() - run-days`,
  when the partition phase runs on SQL Server, then it is emptied with `TRUNCATE TABLE … WITH
  (PARTITIONS (n))`, and once that month is empty in all four tables its boundary is removed with
  `ALTER PARTITION FUNCTION pf_month() MERGE RANGE (…)`. The count is logged, and
  `apiorch.retention.partitions_dropped` is incremented. A partition holding any row newer than the
  cutoff is left alone. SQL Server has no default partition: the open-ended last partition
  (`RANGE RIGHT`, beyond the prepared months) is never truncated; if it is non-empty the job logs
  ERROR and raises `apiorch.retention.default_partition_rows`, because rows there mean the
  maintenance job fell behind (ADR-003 F9).
- [ ] **AC8 (partition creation):** Given `apiorch.retention.partition-months-ahead` (3), when
  `PartitionMaintenanceJob` runs on its own cron, then `pf_month` has boundaries for the current
  month and the next three, created with `ALTER PARTITION SCHEME ps_month NEXT USED [PRIMARY]` then
  `ALTER PARTITION FUNCTION pf_month() SPLIT RANGE (…)` while the split partition is still empty,
  and creating a boundary that already exists is a no-op. Given the **current** month's boundary is
  missing, readiness reports `DOWN` with reason `missing-partition`, so the node is pulled out of
  rotation before inserts start landing in the open-ended last partition (ADR-003 F9).
- [ ] **AC5:** Given a large backlog, when the job runs, then no transaction exceeds 1,000 runs,
  and the job stops when `apiorch.retention.max-runtime-minutes` (60) is reached, resuming on
  the next schedule. Deleted counts are logged and exposed as `apiorch.retention.deleted{table}`.
- [ ] **AC6:** Given two nodes, when the cron fires, then exactly one node runs the job
  (ShedLock), and a run that isn't terminal is never touched.
- [ ] **AC9 (legal hold):** Given a run with `legal_hold = 1` that ended 200 days ago, when
  every phase runs, then its context, payloads and rows are **all** untouched, and
  `POST /api/v1/runs/erase` for it returns `409` `run-on-legal-hold`. A partition containing one of
  its rows is not truncated, and is reported as `apiorch.retention.partitions_held`. Clearing the hold
  with a `reason` makes the run eligible again on the next run of the job, and both the set and the
  clear appear in its timeline.

## Technical details
**Components:** `observability.RetentionJob` runs on `@Scheduled(cron =
"${apiorch.retention.cron}")` with `@SchedulerLock(name="retention-job", lockAtMostFor="PT2H")`.
It has four phases: context, payloads, rows, partitions. Each batch of the first three:
1. `SELECT TOP (1000) id FROM pipeline_run WHERE ended_at < :cutoff AND <phase predicate> ORDER BY
   ended_at`, giving an explicit id list.
2. Run `UPDATE`/`DELETE … WHERE run_id IN (:ids)` per table, in one transaction.

Deletes run in the order `outbox_event`, `run_context`, `step_execution`, `pipeline_run`. The
phases never use `IN (SELECT TOP …)` subqueries, which may re-evaluate differently for each
table.

- `PartitionMaintenanceJob` owns both ends of the partition lifecycle, on its own cron with
  `@SchedulerLock(name="partition-maintenance", lockAtMostFor="PT15M")`. It creates partitions
  `partition-months-ahead` into the future (`NEXT USED` + `SPLIT RANGE`) and truncates and merges
  those fully past `run-days` (AC7), reading the boundaries from `sys.partition_range_values` (via
  `sys.partition_functions` for `pf_month`) and row counts from `sys.partitions`, rather than from a
  naming convention, so a hand-made boundary is handled correctly. Boundaries are built by dynamic
  SQL from `SYSUTCDATETIME()`. It never truncates the open-ended last partition.
- **On H2** (`local` profile and the fast tier) there are no partitions (ADR-003 F8): the
  partition phase is a loop of `DELETE TOP (n) FROM <table> WHERE occurred_at < :cutoff` (the
  table's own time column, held runs excluded) until no rows remain or max-runtime is reached,
  and partition creation and the readiness check are no-ops. The partition path is therefore
  proven only in the `slow` tier on SQL Server.
- `PartitionHealthIndicator` contributes to the readiness group (AC8).
- No foreign key points **into** a partitioned table, because SQL Server refuses `TRUNCATE` on a
  table referenced by a foreign key. The four tables reference `pipeline_run` only by `run_id`, validated by the
  orphan query in AC4, not by a constraint.

**Configuration:**
- `apiorch.retention.payload-days` (30), `apiorch.retention.run-days` (180);
- `apiorch.retention.partition-months-ahead` (3), `apiorch.retention.partition-cron`
  (`0 0 1 * * *`);
- `apiorch.retention.context-hours-after-terminal` (24), `apiorch.retention.resumable-context-days`
  (7);
- `apiorch.retention.cron` (`0 30 2 * * *`), `apiorch.retention.max-runtime-minutes` (60).

**Data:** `pipeline_run.context_purged_at` and `pipeline_run.legal_hold`; `payload_purged` on
`step_attempt`, `callback_inbox` and `run_exchange`.

**API:** `POST /api/v1/runs/{runId}/hold` and `DELETE /api/v1/runs/{runId}/hold`, both
`apiorch.ops`, both requiring a `reason` (README §9).

**Database roles:** the retention and partition jobs use the dedicated role that holds `UPDATE` and
`DELETE` on `run_event` and `step_attempt` (US-7.1 AC7); the application role does not. The job's
`DataSource` is a separate bean, so the rights cannot leak into request handling.

**Metrics:** `apiorch.retention.deleted{table}`, `apiorch.retention.partitions_dropped`,
`apiorch.retention.default_partition_rows`, `apiorch.retention.stuck_runs` and
`apiorch.retention.duration`.

**Test notes**
- `RetentionJobIT` uses a clock fixture with backdated `ended_at`. It covers the context, payload
  and row phases, the resumable rule, the purged flags, the orphan check, max-runtime stop, and
  stuck-run safety (AC1–AC5).
- `RetentionShedLockIT` covers two contexts (AC6).
- `PartitionDropIT` (`slow`, Testcontainers SQL Server) creates backdated boundaries, inserts
  into them, runs the phase, and asserts which are truncated and which are kept, including a
  partition with one row inside the cutoff and a non-empty open-ended last partition (AC7). `RetentionH2FallbackIT` covers the H2 `DELETE TOP (n)`
  loop in the fast tier.
- `PartitionMaintenanceIT` (`slow`, Testcontainers SQL Server) covers creation ahead of time,
  idempotent re-runs and the readiness indicator with the current month's boundary merged away
  (AC8).
- `LegalHoldRetentionIT` covers AC9 across all four phases, the erase refusal and the hold/clear
  events.
