# US-7.7 Log the caller's request and apiorch's response
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-7.3 · **Release:** R1

## User story
As an **Operations Engineer**, I want the request the calling system sent and every response
apiorch returned (including the final result of runs that completed later) kept in the masked
activity log, so that the log covers the whole transaction "from payload to response", not
just the downstream calls.

## Description
The working copy of the input and output in `run_context` is encrypted and deleted soon after
the run ends (US-7.6, US-7.8), so it can't serve as the audit record. This story adds the
**masked, retained** exchange log `run_exchange`:
- **`INBOUND_REQUEST`:** the caller's masked headers and body, plus `bytes`. It is written in the
  run-creation transaction, with event `INBOUND_REQUEST_RECEIVED`.
- **`CALLER_RESPONSE`:** one row per HTTP response to the start request (sync `200`/`202`,
  caller-async `202`, idempotent replays), with status, masked body and bytes. One more row,
  with `final = 1`, is written when the run becomes **terminal**, holding the masked run
  resource. That row records the final result even when the caller received `202` or
  `COMPLETED_PENDING_ASYNC` earlier. Each row records the event `CALLER_RESPONSE_SENT`.

Masking and capture level follow US-7.3: the pipeline's `logging` settings and the global keys.

**Out of scope:** caller webhook bodies, which are recorded as outbox metadata (US-6.5).

## Acceptance criteria
- [ ] **AC1:** Given a sync run started with body `{"name":"Asha","pan":"ABCDE1234F"}` and
  `maskKeys: ["pan"]`, when it is accepted, then a `run_exchange` row `INBOUND_REQUEST` exists
  with masked body `{"name":"Asha","pan":"***"}`, masked headers (`Authorization: ***`),
  `bytes` equal to the real body size, and the `event_seq` of `INBOUND_REQUEST_RECEIVED`.
- [ ] **AC2:** Given the run completes within the wait, when apiorch responds `200`, then a
  `CALLER_RESPONSE` row stores `http_status=200`, the masked response body and `bytes`. The run
  is terminal, so this row also has `final = 1`; no second final row is written.
- [ ] **AC3:** Given a sync run that returns `202` and completes later, then two
  `CALLER_RESPONSE` rows exist: the `202` (`final=0`) and, at the terminal state, the masked
  final run resource (`final=1`, `http_status=null`).
- [ ] **AC4:** Given a run that returns `COMPLETED_PENDING_ASYNC` and later completes, then the
  `200` row has `final=0` and `outputFinal:false` inside its body. A `final=1` row is
  added on completion.
- [ ] **AC5:** Given an idempotent replay (US-4.7), when it responds, then another
  `CALLER_RESPONSE` row is written, with the replay flag in the event details.
- [ ] **AC6:** Given `GET /api/v1/runs/{runId}/exchanges/INBOUND_REQUEST` or `…/CALLER_RESPONSE`
  by `apiorch.ops`, then the rows are returned in order, with masked headers and bodies and
  `payloadPurged`. The timeline (US-7.4) lists them in `calls[]`.
- [ ] **AC7:** Given capture `METADATA`, then the rows hold status and bytes only. Given a
  non-JSON body, the placeholder rule of US-7.3 applies.

## Technical details
**Components**
- `observability.RunExchangeWriter.write(runId, kind, status, headers, body, final)` runs inside
  the caller's transaction and applies `PayloadMasker` and the capture level.
- `RunService.start` (US-4.1) writes `INBOUND_REQUEST` in the creation transaction.
  `RunController` writes `CALLER_RESPONSE` in a short transaction just before the response is
  committed to the socket, locking the run row (README §5.1 rule 1).
- `RunFinalizer` (US-4.6) writes the `final=1` row in the finalizing transaction.

**API contract:** `GET /api/v1/runs/{runId}/exchanges/{kind}` returns
`[{seq, kind, at, httpStatus, headers, body, bytes, truncated, final, payloadPurged}]`
(`apiorch.ops`).

**Data:** Flyway `V7__run_exchange.sql` creates `run_exchange` (README §10), including the
`final` column and an index on (`run_id`, `event_seq`). Its payloads are purged after
`payload-days` (US-7.8).

**Test notes**
- `InboundExchangeIT` covers AC1 and AC7.
- `CallerResponseIT` covers 200, 202 then final, pending-async then final, and replay (AC2–AC5).
- `ExchangeApiIT` covers roles and ordering (AC6).
