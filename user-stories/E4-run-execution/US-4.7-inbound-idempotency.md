# US-4.7 De-duplicate caller retries with Idempotency-Key
**Epic:** E4 Run execution · **Priority:** Must · **Size:** S · **Depends on:** US-4.1 · **Release:** R1

## User story
As a **Calling System**, I want to retry a timed-out or failed request with the same
`Idempotency-Key` and get the original run back instead of a duplicate, so that network
problems between me and apiorch never cause the downstream APIs to be called twice for one
business transaction.

## Description
When `Idempotency-Key` is present, apiorch stores it with a SHA-256 hash of the canonical input.
The key is unique per **pipeline and caller**: `(pipeline_code, created_by, idempotency_key)`.
Two calling systems that happen to use the same order number therefore never collide, and one
caller can't probe another's keys. A repeat with the same key behaves as follows:
- **Same input:** returns the **existing run**, whatever its status. Sync mode waits on it up to
  `syncWaitMs`; caller-async mode returns `202`. The response is marked
  `Idempotent-Replayed: true`.
- **Different input:** `422` `idempotency-key-reuse`.

A replay of a `FAILED` run returns that failed run; it does **not** start a new one. To retry a
failed business transaction safely, the caller asks operations to **resume** it (US-5.8), which
never re-sends steps whose outcome is uncertain. A new key starts an independent run, and steps
that aren't idempotent (for example "create customer") would be called again. This is documented
in the caller guide (US-8.3).

**Out of scope:** idempotency of downstream calls (US-5.2 sends its own outbound key).

## Acceptance criteria
- [ ] **AC1:** Given a first request with `Idempotency-Key: ORD-1001` that created run R, when the
  same caller repeats the identical request, then the response refers to run R, no new
  `pipeline_run` row is created, no downstream call is repeated, and the response carries
  `Idempotent-Replayed: true`.
- [ ] **AC2:** Given R is still running when the repeat arrives in sync mode, then the repeat
  returns `200` once R is done for the caller, or `202` after `syncWaitMs`, exactly like the
  original request.
- [ ] **AC3:** Given the same key and caller with a different body, when submitted, then the
  response is `422` `idempotency-key-reuse` with `detail` naming the original `runId`. Key order
  and whitespace differences don't count as different.
- [ ] **AC4:** Given **another caller** submits the same key for the same pipeline, when
  processed, then it creates its own new run. It never sees the first caller's run ID, receives
  no `422` and gets no `404`.
- [ ] **AC5:** Given two identical requests from the same caller arrive concurrently on
  different nodes, when both are processed, then exactly one run is created, and the other
  request returns that run.
- [ ] **AC6:** Given a replay of a run that ended `FAILED`, when received, then the response is
  `200` with that run (`status: FAILED`, `Idempotent-Replayed: true`), and no downstream call is
  made.
- [ ] **AC7:** Given a replay while this node is at its admission limit (US-5.4) or the pipeline
  is disabled (US-3.7), when received, then the existing run is still returned. Replays are
  looked up **before** the admission and enabled checks, because they create no new work.
- [ ] **AC8:** Given a key over 128 characters or outside `[A-Za-z0-9._:-]`, the response is
  `400` `validation-failed`. Without a key, every request creates a new run.

## Technical details
**Components:** `run.IdempotencyService`
1. `findExisting(code, principal, key)` is a plain `SELECT` on the unique index, done before any
   admission or enabled check. If a row exists, compare `request_hash`: if equal it is a replay,
   otherwise `422`.
2. Otherwise insert the new run (US-4.1). On `DataIntegrityViolationException` for the unique
   key, re-select and treat it as a replay (the race in AC5).

The canonical hash sorts object keys, keeps array order and uses UTF-8.

**Data:** `pipeline_run.idempotency_key` (nullable), `request_hash`, and UNIQUE(`pipeline_code`,
`created_by`, `idempotency_key`). Runs started without a key must stay unconstrained, but SQL
Server allows only one NULL in a unique index and H2 has no filtered indexes (ADR-003 F16), so the
index is a vendor migration: `sqlserver/` creates a filtered unique index
`WHERE idempotency_key IS NOT NULL`, and `h2/` the same version as a plain unique index. Keys are
retained as long as the run row (US-7.8, `run-days`).

**Logging:** INFO `idempotent replay runId=… key=ORD-1001`; WARN `idempotency key reuse with
different payload`, without bodies.

**Test notes**
- `IdempotencyIT` covers AC1–AC3, AC6 and AC8.
- `IdempotencyCrossCallerIT` covers AC4.
- `IdempotencyRaceIT` runs 20 concurrent requests across two contexts and asserts one run plus
  one call per step (AC5); it also runs in the `slow` tier on SQL Server (ADR-003 rule 6).
- `IdempotencyNullKeySqlServerIT` (`slow`) inserts two runs of the same pipeline and caller
  without a key and expects both to succeed (ADR-003 F16).
- `IdempotencyAdmissionIT` covers AC7. US-5.4 is in R1, so the admission half runs from R1; the
  `pipeline-disabled` half (US-3.7) is tagged `pending-R2` until that story lands.
