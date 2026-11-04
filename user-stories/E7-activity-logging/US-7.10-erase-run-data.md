# US-7.10 Erase a run's personal data on request
**Epic:** E7 Activity logging & data protection · **Priority:** Should · **Size:** S · **Depends on:** US-7.8 · **Release:** R2

## User story
As a **Security Officer**, I want to erase the personal data held for specific runs (by run
id or business correlation id) on a data-principal request, with an audited record that it was
done, so that apiorch can meet data-protection obligations without deleting the operational
metadata needed for audits.

## Description
Erasure removes **content** but keeps **facts**.

**Removed:**
- all `run_context` entries;
- the bodies and headers in `step_attempt`, `callback_inbox` and `run_exchange`;
- the outbox payloads.

**Kept:** statuses, timings, sizes, error codes, events (which never contain bodies, US-7.1),
and the `RUN_DATA_ERASED` record itself.

Only runs that are **terminal** can be erased. Non-terminal runs are reported back so that
operations can cancel them first (US-5.7).

The exact scope (for example, whether masked logs must also go) is subject to compliance
sign-off (open question Q1). The design supports removing more, but not less.

**Out of scope:** erasure inside downstream systems and searching by personal attributes. The
caller must supply run or correlation ids.

## Acceptance criteria
- [ ] **AC1:** Given terminal runs R1 and R2 with `correlationId = CUST-881`, when an operator
  calls `POST /api/v1/runs/erase` with `{"correlationId":"CUST-881","reason":"DPDP request
  DR-102"}`, then for both runs the context rows are deleted, the payload columns are NULL with
  `payload_purged=true`, and `context_purged_at` is set. The response is
  `200 {"erased":["R1","R2"],"skipped":[]}`.
- [ ] **AC2:** Given an erased run, when its timeline is requested, then the statuses, durations,
  sizes and order are intact, and the detail APIs return `payloadPurged: true`.
- [ ] **AC3:** Given a non-terminal run among the matches, when erasing, then it is listed in
  `skipped` with `reason: "NOT_TERMINAL"`, and nothing of it is changed.
- [ ] **AC4:** Given each erased run, then the event `RUN_DATA_ERASED` `{actor, reason,
  scope:"CONTENT"}` is recorded, and a WARN log line is written. A request without `reason`
  (1–500 characters) is rejected with `400`.
- [ ] **AC5:** Given `{"runIds":[…]}` with up to 100 ids, when erasing, then the same rules apply
  per id. Unknown ids appear in `skipped` with `reason: "NOT_FOUND"`.

## Technical details
**API contract:** `POST /api/v1/runs/erase` takes `{ "runIds": [uuid…]? , "correlationId": "…"?,
"reason": "…" }`, where exactly one of `runIds` or `correlationId` is required. It returns
`200 { "erased": [ids], "skipped": [{ "runId", "reason" }] }` and requires `apiorch.ops`.

**Components:** `observability.ErasureService` reuses the per-table purge statements of
`RetentionJob` (US-7.8), applied to an explicit id list in one transaction per run. It takes the
run row lock first (README §5.1).

**Test notes**
- `ErasureIT` covers a correlation-id match, an id list, a non-terminal run being skipped, an
  unknown id, the timeline staying intact after erasure, the event and log, and the reason
  being required (AC1–AC5).
