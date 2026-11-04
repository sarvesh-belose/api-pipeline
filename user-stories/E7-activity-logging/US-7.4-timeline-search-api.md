# US-7.4 Query a run's timeline and search runs
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-7.2, US-7.7 · **Release:** R1

## User story
As an **Operations Engineer** (and as a **Calling System** for my own runs), I want one API that
shows everything a run exchanged, in order: the caller's request, each downstream call with
its response time and sizes, each callback, and the response apiorch returned. I also want the
details of any exchange and a way to search runs, so that I can answer "what happened to
transaction X?" in seconds without reading raw logs.

## Description
There are four read APIs:
1. **Timeline**, `GET /api/v1/runs/{runId}/timeline`. It contains:
   - a run summary and step summaries (with `execNo`);
   - an ordered list of **calls**, merged by `event_seq`:
     - `INBOUND_REQUEST` and `CALLER_RESPONSE` from `run_exchange` (US-7.7);
     - `REQUEST` and `COMPENSATION` attempts from `step_attempt`, including rejections;
     - `CALLBACK` entries from `callback_inbox`;
   - optionally the full event sequence;
   - totals.
2. **Attempt detail**, `GET …/steps/{stepId}/attempts/{n}?kind=`: masked headers and bodies.
3. **Callback detail**, `GET …/steps/{stepId}/callbacks/{n}`: the masked callback headers and
   body, so it shows what the async API actually sent back.
4. **Search**, `GET /api/v1/runs`, with filters and pagination.

Exchange detail is US-7.7. Callers with `apiorch.run` see only their own runs (US-4.2), and never
see bodies; the detail endpoints require `apiorch.ops`.

**Reading a payload is itself an auditable act.** The detail endpoints return masked PAN, Aadhaar
and other personal data to a human operator, and until now nothing recorded who looked. Each
successful attempt, callback or exchange detail response records a `PAYLOAD_ACCESSED` event with
`{actor, kind, stepId, execNo, attemptNo}`. A forbidden attempt records nothing (it saw nothing)
but increments a metric. Without this, the only question an audit cannot answer about this system
is who read the data.

**Out of scope:** a UI. Third-party tools can consume these JSON APIs.

## Acceptance criteria
- [ ] **AC1:** Given a completed 5-step run, when its creator calls `GET …/timeline`, then
  `calls[]` lists, in order:
  1. `INBOUND_REQUEST`;
  2. 5 `REQUEST` entries, each with `seq`, `stepId`, `execNo`, `attempt`, `endpoint`, `method`,
     `url` (masked), `startedAt`, `durationMs`, `requestBytes`, `responseBytes`, `httpStatus`,
     `outcome` and `circuitState`;
  3. `CALLER_RESPONSE`.

  `steps[]` lists each step's final status. `totals` gives `calls`, `requestBytes`,
  `responseBytes`, `downstreamTimeMs` (the sum of downstream call durations) and `wallTimeMs`.
- [ ] **AC2:** Given a run with retries, a rejected attempt, an async callback, a resume and a
  compensation, when the timeline is requested, then `calls[]` includes every attempt of every
  execution: `CIRCUIT_OPEN` rows, `CALLBACK` entries (`receivedAt`, `bodyBytes`, `status`
  `ACCEPTED|DUPLICATE|LATE|REJECTED`, `execNo`) and `COMPENSATION` entries, all ordered by `seq`.
- [ ] **AC3:** Given `?include=events`, when requested, then `events[]` lists every `run_event`
  (`seq`, `type`, `stepId`, `execNo`, `attempt`, `at`, `details`, `actor`). Without it, events
  are omitted.
- [ ] **AC4:** Given an operator, when they call `GET …/steps/api2/attempts/1?kind=REQUEST`, then
  they receive masked `requestHeaders`, `requestBody`, `responseHeaders`, `responseBody`, the
  truncation flags and `payloadPurged`. A caller with only `apiorch.run` gets `403`. A purged
  attempt returns metadata with `payloadPurged: true` and null bodies.
- [ ] **AC9 (payload access is recorded):** Given an operator who successfully reads an attempt,
  callback or exchange detail, then a `PAYLOAD_ACCESSED` event is recorded against that run with
  `{actor, kind, stepId?, execNo?, attemptNo?}`, visible in the timeline's `events[]`. Reading the
  timeline or the search API records nothing, because neither returns a body. A `403` records no
  event and increments `apiorch.payload.access.denied`.
- [ ] **AC5:** Given an operator, when they call `GET …/steps/api4/callbacks/1` (the first
  callback by `received_at`), then they receive the masked callback `headers`, `body`,
  `bodyBytes`, `status` and `execNo`. LATE and DUPLICATE callbacks are included, so what the
  async API actually sent is always visible.
- [ ] **AC6:** Given `GET /api/v1/runs?pipeline=CUSTOMER_ONBOARD&status=FAILED&status=TIMED_OUT&from=2026-11-01T00:00:00Z&to=2026-11-04T00:00:00Z&page=0&size=50`
  from an operator, when called, then the matching runs are returned newest first by
  `created_at`, as summaries (`runId, pipeline, version, status, correlationId, createdAt,
  durationMs, createdBy, errorCode`), with `page`, `size`, `totalElements` and `totalPages`.
  `size` is at most 100. `from` and `to` filter on `created_at`, so runs that never started are
  found too.
- [ ] **AC7:** Given `?correlationId=abc-123`, the runs with that id are returned. Given a caller
  with `apiorch.run`, results are restricted to `created_by = principal` whatever the filters.
- [ ] **AC8:** Given a timeline for a run with 50 steps and 3 attempts each, when requested, then
  the number of SQL statements is constant: at most 6, whatever the step count. The latency
  target is part of the US-8.2 suite.

## Technical details
**API contract:** `GET /api/v1/runs/{runId}/timeline[?include=events]`
```json
{
  "run": { "runId": "…", "pipeline": "CUSTOMER_ONBOARD", "version": 2, "status": "COMPLETED",
           "correlationId": "abc-123", "createdAt": "…", "startedAt": "…", "endedAt": "…", "resumeCount": 0 },
  "steps": [ { "stepId": "api1", "execNo": 1, "status": "SUCCEEDED", "attempts": 1, "durationMs": 120, "httpStatus": 201,
               "skipReason": null, "fallbackApplied": false, "compensationStatus": "NOT_REQUIRED" } ],
  "calls": [
    { "seq": 1, "type": "INBOUND_REQUEST", "at": "…", "bytes": 214 },
    { "seq": 4, "type": "REQUEST", "stepId": "api1", "execNo": 1, "attempt": 1, "endpoint": "customer-svc",
      "method": "POST", "url": "https://customer.internal/api/customers", "startedAt": "…",
      "durationMs": 120, "requestBytes": 312, "responseBytes": 1024, "httpStatus": 201,
      "outcome": "SUCCESS", "circuitState": "CLOSED" },
    { "seq": 21, "type": "CALLBACK", "stepId": "api4", "execNo": 1, "receivedAt": "…", "bodyBytes": 210, "status": "ACCEPTED" },
    { "seq": 23, "type": "CALLER_RESPONSE", "at": "…", "httpStatus": 200, "bytes": 640, "final": false }
  ],
  "totals": { "calls": 7, "requestBytes": 2810, "responseBytes": 9120, "downstreamTimeMs": 1460, "wallTimeMs": 3210 }
}
```
- `GET /api/v1/runs/{runId}/steps/{stepId}/attempts/{n}?kind=REQUEST|COMPENSATION`: `n` is
  `attempt_no`, which is unique per run, step and kind across executions (US-5.8).
- `GET /api/v1/runs/{runId}/steps/{stepId}/callbacks/{n}`: `n` is the 1-based order by
  `received_at`.
- `GET /api/v1/runs` takes `pipeline`, `status` (repeatable), `from`/`to` (on `created_at`),
  `correlationId`, `createdBy` (ops only), `page` and `size`.

**Components** (`com.crisil.apiorch.run`)
- `TimelineAssembler` runs one query each for the run, steps, attempts, callbacks and exchanges,
  plus events when requested. It merges rows by their stored `event_seq` (no join through
  events), and computes totals.
  - The run row is read **first**, and every subsequent query carries a time bound derived from it
    (`created_at` to `COALESCE(ended_at, SYSUTCDATETIME())`, widened by one day), applied as a
    range on each table's partitioning column (`occurred_at`, `started_at`, `created_at`). Without
    it, a query by `run_id` alone scans every month partition of `run_event`, `step_attempt`,
    `run_exchange` and `callback_inbox` on SQL Server (README §10, ADR-003 F9).
    `TimelineQueryCountIT` asserts the statement count; a companion `slow`-tagged
    `TimelinePartitionEliminationIT` on Testcontainers SQL Server asserts from the actual
    execution plan that partition elimination happened (H2 has no partitions, ADR-003 F8).
- `RunSearchService` uses Spring Data JPA `Specification`s over the read-only `pipeline_run`
  entity, sorted by `created_at DESC`. Paging is `ORDER BY … OFFSET … ROWS FETCH NEXT … ROWS
  ONLY` on both databases (ADR-003 F15).
- Ownership is shared with `RunQueryService` (US-4.2).

**Security:** the timeline and search allow `apiorch.run` (own runs) and `apiorch.ops`. Attempt,
callback and exchange detail require `apiorch.ops` (US-1.2). `PayloadAccessRecorder` writes the
`PAYLOAD_ACCESSED` event through `ActivityRecorder` in its own short transaction, after the
response body is assembled and before it is written, so a recorded access always corresponds to
data that was actually returned.

**Raw (unmasked) values** are never served by any API in this backlog. US-7.3 directs
investigations that need them to the operator-restricted run context, which in practice means
someone runs SQL against production — the least auditable path in the system. If that need is real,
it should become a story with an endpoint and a `PAYLOAD_ACCESSED` event of its own rather than
staying an undocumented database query. Raised as open question Q7.

**Test notes**
- `TimelineIT` covers a simple run, retries with a rejection, async, resume and compensation,
  checking order, fields, totals and `include=events` (AC1–AC3).
- `AttemptDetailIT` covers AC4.
- `PayloadAccessAuditIT` covers AC9: a successful read, a `403`, and the absence of an event for
  timeline and search reads.
- `CallbackDetailIT` covers accepted, late and duplicate callbacks (AC5).
- `RunSearchIT` covers AC6 and AC7.
- `TimelineQueryCountIT` uses a datasource-proxy statement counter (AC8).
