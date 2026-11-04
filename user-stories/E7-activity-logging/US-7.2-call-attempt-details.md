# US-7.2 Record request, response, response time and sizes of every call attempt
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-7.1, US-4.3 · **Release:** R1

## User story
As an **Operations Engineer**, I want every HTTP exchange with a downstream API recorded with
its request, response, response time and payload sizes, including failed attempts, retries,
rejections and compensations, so that I can prove what was sent and received and diagnose slow
or failing systems.

## Description
Every attempt writes **exactly one** `step_attempt` row. That covers each retry, circuit or
bulkhead rejections (where nothing was sent), and compensation calls
(`kind = COMPENSATION`). The row holds:
- the method and resolved URL;
- request headers and body;
- response status, headers and body;
- **duration in ms**;
- the **true request and response sizes in bytes**;
- outcome, error class, circuit state and retry delay;
- `exec_no`;
- `event_seq`, the `seq` of the event that started the attempt: `REQUEST_SENT`,
  `COMPENSATION_REQUEST_SENT`, `CIRCUIT_OPEN_REJECTED` or `BULKHEAD_REJECTED`.

`event_seq` places every attempt in the run's global order (US-7.4).

What is stored, and how it is masked, follows the capture level (US-7.3, default `METADATA`; the
metadata in AC1 is stored at **every** level). Bodies
over `maxBodyBytes` are truncated with a flag, but the **size columns always hold the real
size**. A token-refresh repeat (US-2.2) is part of one attempt: only the final exchange is
recorded.

**Out of scope:** callback payloads (US-6.2) and the caller exchange (US-7.7). The timeline
(US-7.4) presents both alongside attempts.

## Acceptance criteria
- [ ] **AC1:** Given api1 POSTs a 312-byte JSON body and receives `201` with 1,024 bytes, when the
  attempt ends, then a `step_attempt` row exists with `exec_no=1`, `attempt_no=1`,
  `kind=REQUEST`, `event_seq`, `endpoint_name`, `method=POST`, the full resolved `url`,
  `request_bytes=312`, `response_bytes=1024`, `response_status=201`, `outcome=SUCCESS`,
  `circuit_state=CLOSED`, `started_at`, `ended_at` and `duration_ms`.
- [ ] **AC2:** Given api2 fails 503, 503, then 200 (US-5.2), when it ends, then 3 rows exist
  (`attempt_no` 1–3). Rows 1 and 2 have `outcome=HTTP_ERROR`, `response_status=503` and
  `next_retry_delay_ms` > 0, and row 3 has `SUCCESS`. Their `event_seq` values are increasing.
- [ ] **AC3:** Given a 2 MB JSON response and `maxBodyBytes=262144`, when stored, then
  `response_body` holds the **masked** document truncated to 262,144 bytes at a UTF-8 boundary,
  with `response_truncated=true` and `response_bytes=2097152`.
- [ ] **AC4:** Given a WireMock `fixedDelay(750)`, when the attempt ends, then `duration_ms` is
  at least 750 and under 1,500. It is measured from just before sending until the response body
  is fully read.
- [ ] **AC5:** Given a `TIMEOUT`, `DEADLINE_EXCEEDED`, `CONNECT_ERROR` or `IO_ERROR` attempt,
  then `response_status` is null, `outcome` is set, `error_class` is the simple name, and
  `error_message` is at most 1,000 characters. Given a `CIRCUIT_OPEN` or `BULKHEAD_FULL`
  rejection, a row is stored with `duration_ms=0`, `request_bytes=0`, no request body, and the
  rejection event's `event_seq`.
- [ ] **AC6:** Given the final attempt, then its row and the `STEP_SUCCEEDED`/`STEP_FAILED` event
  commit in the same fenced transaction. Intermediate attempts commit with their
  `ATTEMPT_FAILED` event.
- [ ] **AC7:** Given any step, then `step_execution.attempts` equals the number of its
  `kind=REQUEST` rows for the current execution. Given an OAuth2 401 refresh-and-repeat (US-2.2),
  exactly one row is written.

## Technical details
**Components**
- `http.ExchangeRecorder` is a `ClientHttpRequestInterceptor`, registered **outermost** on each
  endpoint `RestClient`, outside the auth interceptor (US-2.2). It captures request headers and
  body bytes, then wraps the response body in a **counting, size-limited tee**. The tee counts
  every byte read; it keeps up to `apiorch.run.max-response-bytes` for parsing and hands the
  parsed document to masking. Timing uses `System.nanoTime()`.
- `observability.AttemptWriter.write(AttemptRecord)` applies the capture level and masking
  (US-7.3), **then** truncates, then inserts the row in the caller's fenced transaction. Headers
  are stored as a JSON object of masked values.
- `ResilientHttpClient` builds `AttemptRecord`s for rejections that never reach the interceptor
  (`CIRCUIT_OPEN`, `BULKHEAD_FULL`) and for budget pre-check failures (`DEADLINE_EXCEEDED`).
- `request_bytes` is the length of the serialized body as sent. Header bytes are not counted;
  this is documented.

**Data:** Flyway `V6__step_attempt.sql` is a vendor pair (ADR-003 rule 2) creating `step_attempt`
(README §10), with `id UNIQUEIDENTIFIER`, `started_at DATETIME2(6)` (UTC), `url VARCHAR(2048)`,
`*_headers` and `*_body NVARCHAR(MAX)`, `*_bytes BIGINT`, `duration_ms BIGINT`, `event_seq BIGINT`,
PK(`id`, `started_at`) and UNIQUE(`run_id`, `step_id`, `kind`, `attempt_no`, `started_at`). The
`sqlserver/` file makes the PK clustered `ON ps_month(started_at)`, the same monthly scheme as
`run_event` (ADR-003 F9); the `h2/` file is a plain table. Headers and bodies are stored
opaquely, masked and size-capped, and are never filtered on; no JSON column is queried in SQL
(ADR-003 F15, US-7.1). The row is **inserted once, complete**, when the attempt ends (AC6), so it
is routed to its partition on insert and never updated afterwards — which is what lets the
application role hold
`INSERT`/`SELECT` only (US-7.1 AC7).

**Configuration:** `apiorch.logging.default-capture` (`METADATA`) and
`apiorch.logging.default-max-body-bytes` (262144), overridable per pipeline or step (US-7.3).

**Metrics** (US-7.5): `apiorch.payload.request.bytes` and `apiorch.payload.response.bytes`
(tag `endpoint`), and `apiorch.step.duration`.

**Test notes**
- `AttemptCaptureIT` covers AC1 and AC4.
- `AttemptRetryRowsIT` covers AC2.
- `AttemptTruncationIT` uses 2 MB of multibyte UTF-8 and checks masking before truncation (AC3).
- `AttemptErrorRowsIT` uses faults, a closed port, an open circuit, a full bulkhead and an
  exhausted budget (AC5).
- `AttemptTransactionIT` covers AC6.
- `AttemptCountConsistencyIT` includes the OAuth2 refresh case (AC7).
