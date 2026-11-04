# US-7.1 Activity recorder and run event log
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-4.1 · **Release:** R1

## User story
As an **Operations Engineer**, I want an ordered, durable log of everything that happens in a
run (each step scheduled, each request sent, each response received, retries, skips, callbacks,
operator actions and the final outcome), so that I can reconstruct exactly which APIs were
called, in what sequence, and why the run ended as it did.

## Description
`run_event` is an **append-only** table keyed by (`run_id`, `seq`). `seq` is gap-free and
strictly increasing per run. It is allocated from `pipeline_run.event_seq` inside the **same
transaction** as the state change it describes, a transaction that already holds the run row
lock (README §5.1). Every component records events through one `ActivityRecorder` API, which
also writes a matching structured log line with MDC (US-1.3). The log line is written
`afterCommit`, so logs never report something that was rolled back.

This story is a **foundation** delivered right after US-4.1. Step-level stories (US-4.3 onwards)
depend on it and assert their own events; the full 5-step sequence is US-4.3 AC8. Event
`details` are small JSON objects with statuses, sizes, durations, codes and actors. **Bodies are
never stored in events**; they belong to `step_attempt`, `callback_inbox` and `run_exchange`.

**Append-only is enforced by the database, not by convention.** The application connects as a role
with `INSERT` and `SELECT` on `run_event` and `step_attempt` and **no** `UPDATE` or `DELETE`, so a
bug or an injected statement cannot rewrite history. Purging and partition maintenance (US-7.8) and
erasure (US-7.10) run as a separate role with those rights, used by nothing else. Without this the
word "append-only" is a comment, and an auditor is entitled to ask what stops a rewrite.

**Out of scope:** HTTP payload capture (US-7.2), masking (US-7.3) and query APIs (US-7.4).
Tamper **evidence** — a per-run hash chain over events — is also out of scope: the grant model
stops the application from rewriting rows, and a chain additionally detects someone with database
rights doing it. That is a separate control, raised as open question Q6.

## Acceptance criteria
- [ ] **AC1:** Given a run is accepted and started (US-4.1, stub executor), then `run_event`
  holds `RUN_ACCEPTED` (seq 1), committed in the **same** transaction as the `pipeline_run`
  insert, and `RUN_STARTED` (seq 2), committed with the transition to `RUNNING`.
- [ ] **AC2:** Given 50 concurrent writers for the same run, each in its own transaction that
  locks the run row and records one event, then exactly 50 events exist, with `seq` 1…50,
  gap-free and unique. Their `occurred_at` order agrees with `seq`.
- [ ] **AC3:** Given a transaction that records an event and then rolls back, then neither the
  event nor the state change persists, and **no** log line for that event is written.
- [ ] **AC4:** Given `ActivityRecorder.record` is called outside a transaction, then it throws
  `IllegalTransactionStateException` (propagation `MANDATORY`).
- [ ] **AC5:** Given events of each type in the catalog, then their `details` contain exactly the
  documented keys, and no request or response body appears in any event. Operator events carry
  `actor` (JWT principal) and `reason`.
- [ ] **AC6:** Given any recorded event, then after commit a log line at INFO (WARN for failures)
  is emitted with `event=<TYPE>`, the MDC fields, and the same details as the row.
- [ ] **AC7 (append-only is enforced):** Given the application's database role, when it attempts
  `UPDATE run_event`, `DELETE FROM run_event`, `UPDATE step_attempt` or `DELETE FROM step_attempt`,
  then SQL Server refuses with permission denied (error 229). `INSERT` and `SELECT` succeed. Given the
  retention role, all four succeed. This is asserted directly against both roles, not through the
  application.

## Technical details
**Event catalog** (owner stories in README §10)
| Event | Details (JSON) |
|---|---|
| `RUN_ACCEPTED` | `{mode, version, inputBytes, idempotencyKey?}` |
| `RUN_STARTED` / `RUN_RESUMED` / `RUN_RECOVERED` | `{segmentDeadline, traceId?, spanId?}` / `{actor, reason, resetSteps}` / `{previousOwner, newOwner, epoch}` |
| `STEP_SCHEDULED` | `{stepId, execNo, endpoint, mode}` |
| `REQUEST_SENT` / `COMPENSATION_REQUEST_SENT` | `{stepId, execNo, attempt, method, url (masked), requestBytes}` |
| `RESPONSE_RECEIVED` | `{stepId, attempt, httpStatus, durationMs, requestBytes, responseBytes, outcome, endpoint}` |
| `ATTEMPT_FAILED` / `RETRY_SCHEDULED` | `{attempt, outcome, httpStatus?, errorClass?}` / `{attempt, delayMs, reason}` |
| `CIRCUIT_OPEN_REJECTED` / `BULKHEAD_REJECTED` / `TOKEN_REFRESHED` | `{endpoint, attempt}` |
| `STEP_SUCCEEDED` / `STEP_FAILED` | `{attempts, durationMs, httpStatus}` / `{code, message, httpStatus, policy}` |
| `STEP_SKIPPED` / `STEP_FALLBACK_APPLIED` / `STEP_OUTCOME_UNKNOWN` / `STEP_RESOLVED` | `{reason, dependency?}` / `{errorCode}` / `{cause}` / `{actor, reason, outcome}` |
| `ASYNC_ACCEPTED` / `ASYNC_ACK_SUPERSEDED_BY_CALLBACK` | `{ackStatus, deadlineAt, execNo}` / `{execNo, ackOutcome, ackError}` |
| `CALLBACK_RECEIVED` / `CALLBACK_DUPLICATE` / `CALLBACK_LATE` / `CALLBACK_REJECTED` | `{execNo, bodyBytes, outcome?}` / `{execNo}` / `{stepStatus, execNo}` / `{reason, sourceIp}` |
| `CALLBACK_TIMED_OUT` | `{deadlineAt, policy}` |
| `RUN_WAITING_CALLBACK` / `RUN_COMPLETED_PENDING_ASYNC` | `{pending: [...]}` |
| `RUN_COMPLETED` / `RUN_COMPLETED_WITH_ERRORS` / `RUN_FAILED` / `RUN_TIMED_OUT` | `{durationMs, steps, errors, errorCode?, aborted?, unknown?}` |
| `RUN_CANCEL_REQUESTED` / `RUN_CANCELLED` | `{actor, reason, compensate}` |
| `COMPENSATION_*` | `{stepId?, attempts?, outcome?}` |
| `INBOUND_REQUEST_RECEIVED` / `CALLER_RESPONSE_SENT` | `{bytes}` / `{httpStatus, bytes, final}` |
| `CALLER_WEBHOOK_SENT` / `CALLER_WEBHOOK_FAILED` / `CALLER_WEBHOOK_REDELIVERED` | `{event, attempts, httpStatus}` / same / `{actor, reason}` |
| `PAYLOAD_ACCESSED` | `{actor, kind, stepId?, execNo?, attemptNo?}` |
| `RUN_HOLD_SET` / `RUN_HOLD_CLEARED` | `{actor, reason}` |
| `RUN_DATA_ERASED` | `{actor, reason, scope}` |

**Components** (`com.crisil.apiorch.observability`)
- `ActivityRecorder.record(runId, EventType, stepId?, execNo?, attempt?, Map details, actor?)`
  has `@Transactional(propagation = MANDATORY)`. It does the following:
  1. `UPDATE pipeline_run SET event_seq = event_seq + 1 WHERE id = ?`, then reads it back,
     through `JdbcClient` (README §5.1 rule 2). The caller already holds the run lock.
  2. Inserts `run_event` with `occurred_at = SYSUTCDATETIME()`, `instance_id` and `details`
     (Jackson, at most 4 KB; truncated with a `truncated` flag).
  3. Registers a `TransactionSynchronization.afterCommit` that logs `event=<TYPE>` with
     `StructuredArguments.entries(details)`.

  It returns the `seq`, which attempt and inbox rows store as `event_seq` (US-7.2, US-6.2).
- `EventType` is an enum of the catalog.

**Data:** Flyway `V5__run_event.sql` is a vendor pair (ADR-003 rule 2).
- `sqlserver/V5__run_event.sql` creates the monthly partition function `pf_month (DATETIME2(6)) AS
  RANGE RIGHT` (boundaries for the current and next months, built by dynamic SQL from
  `SYSUTCDATETIME()`), the scheme `ps_month`, and `run_event(run_id UNIQUEIDENTIFIER, seq BIGINT,
  event_type VARCHAR(48), step_id VARCHAR(64), exec_no INT, attempt INT, occurred_at DATETIME2(6)
  (UTC), instance_id VARCHAR(128), actor NVARCHAR(256), details NVARCHAR(MAX), CONSTRAINT
  pk_run_event PRIMARY KEY CLUSTERED (run_id, seq, occurred_at) ON ps_month(occurred_at),
  CONSTRAINT ck_run_event_details_json CHECK (details IS NULL OR ISJSON(details) = 1))`
  (README §10, ADR-003 F9). It also creates the grants (below).
- `h2/V5__run_event.sql` creates the same columns as a plain table with the same primary key and
  no partitioning, `ISJSON` check or grants (ADR-003 F8).
- `occurred_at` is in the clustered key because SQL Server requires the partitioning column there
  (ADR-003 F9). Gap-free, unique `seq` per run comes from the counter allocated under the run row
  lock, not from the index.
- `details` is stored, returned and checked with `ISJSON`, **never queried** (ADR-003 F15). The
  fields investigations filter on (`step_id`, `event_type`, `exec_no`, `attempt`, `actor`) are
  their own columns, written at insert time; a new filter means a new column, not `JSON_VALUE`.
- **Grants** (`sqlserver/` only): `CREATE ROLE apiorch_app` with `GRANT SELECT, INSERT ON
  run_event` and `ON step_attempt`, and no `UPDATE` or `DELETE`; a separate retention role holds
  `SELECT, INSERT, UPDATE, DELETE` (ADR-003 F10).

**Volume note:** about 4 events per step attempt. At the NFR load (README §14) that is roughly
2,000 inserts/s and billions of rows at 180-day retention, which is why the table is partitioned
and reclaimed by truncating partitions rather than deleting rows (US-7.8). Sized by US-8.2.

**Test notes**
- `RunLifecycleEventsIT` covers AC1.
- `EventSeqConcurrencyIT` runs 50 threads (AC2). It runs on both tiers; the `slow` run on
  Testcontainers SQL Server is the evidence (ADR-003 rule 6, F14).
- `RunEventPartitionIT` (`slow`, SQL Server only) asserts that a run's events land in the month
  partition for their `occurred_at` (`$PARTITION.pf_month`), and that a row outside the prepared
  range lands in the first or last partition rather than failing the insert. `RunEventJsonCheckIT`
  (`slow`) asserts invalid `details` is rejected with error 547 (ADR-003 F8).
- `EventTransactionIT` covers rollback with no row and no log line, and the MANDATORY
  propagation (AC3, AC4).
- `EventDetailsContractTest` checks the keys for each type (AC5).
- `EventLogLineTest` uses a ListAppender (AC6).
- `AppendOnlyGrantsIT` (`slow`, SQL Server only) runs each statement under `EXECUTE AS USER` a
  member of each role and asserts the permitted and refused statements on both tables (AC7). A
  refusal is identified by **vendor error code 229** (`SQLException.getErrorCode()`, SQLState
  `S0005`) on the root `SQLException`. Spring translates it to `UncategorizedSQLException`, so
  asserting on the exception type or message would be wrong (ADR-003 F10, observed in the probe).
  The same rule applies to any
  production code that classifies a privilege failure, for example a misconfigured role surfacing
  in the runbook (US-8.4).
