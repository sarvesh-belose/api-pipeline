# US-6.5 Notify the calling system via webhook (transactional outbox)
**Epic:** E6 Async steps · **Priority:** Must · **Size:** M · **Depends on:** US-4.2, US-6.3 · **Release:** R2

## User story
As a **Calling System**, I want apiorch to call my webhook when my run is complete (including
`COMPLETED_PENDING_ASYNC` and the later final completion) or fails, so that I don't have to poll,
and I can trust that the notification is genuine, ordered, and will arrive even if apiorch
restarts.

## Description
A caller passes `X-Callback-Url: https://caller.example.com/hooks/apiorch` when starting a run.
Each time the run reaches a notifiable status, an `outbox_event` row is written **in the same
transaction** as the status change. The payload is the run resource, **encrypted at rest**
(US-7.6).

Every node runs an `OutboxDispatcher` that claims rows **one by one**. It doesn't use a global
lock, so a slow receiver never blocks the whole outbox. Delivery is:
- in order per run;
- signed with HMAC;
- in parallel up to a global and a per-host limit;
- retried with exponential backoff up to `max-attempts`.

Delivery is **at least once**: receivers de-duplicate on `X-Apiorch-Event-Id`. The URL host
must be on an allow-list and must not resolve to a private address (anti-SSRF). Operators can
redeliver `DEAD` events.

**Ordering stops at a `DEAD` row.** A later event is held while an older one for the same run is
still `PENDING` or `SENDING`, but `DEAD` is terminal, so without a rule the stream would silently
resume out of order — the receiver would see `run.completed` having never seen
`run.pending_async`, and nothing would say so. The run's stream is therefore **blocked** when one of
its events dies: later rows stay `PENDING` with `last_error = BLOCKED_BY_DEAD_PREDECESSOR` and are
not sent. The run appears in `apiorch.outbox.blocked_runs`, and an operator either redelivers the
dead event (AC9), which unblocks the rest in order, or abandons the stream explicitly. Silent
reordering of business notifications is the one outcome worth stopping the queue for.

**Out of scope:** per-caller signing keys and receiver registration.

## Acceptance criteria
- [ ] **AC1:** Given a run started with an allowed `X-Callback-Url`, when it becomes `COMPLETED`,
  then the receiver gets `POST` with the run resource as the body and the headers
  `X-Apiorch-Event: run.completed`, `X-Apiorch-Event-Id: <uuid>`, `X-Correlation-Id`,
  `X-Signature: t=<unix>,v1=<hex>` and `Content-Type: application/json`. The row becomes `SENT`,
  and `CALLER_WEBHOOK_SENT` is recorded.
- [ ] **AC2:** Given the US-6.3 flow, when the run reaches `COMPLETED_PENDING_ASYNC` and later
  `COMPLETED`, then two webhooks are delivered **in that order**: `run.pending_async`, then
  `run.completed`. A later event for a run is never sent while an earlier one is undelivered.
- [ ] **AC3:** Given the receiver verifies `v1 = HMAC-SHA256(signingKey, t + "." + rawBody)`, then
  the signature matches, and any change to the body or `t` breaks it. The caller guide (US-8.3)
  documents verification and recommends rejecting `t` older than 5 minutes.
- [ ] **AC4:** Given the receiver returns `503` (or times out) three times and then `200`, then
  retries use exponential backoff (2 s, 4 s, 8 s, … capped at 10 min, ±20% jitter) and the row
  ends `SENT` with `attempts = 4`, where `attempts` counts every delivery attempt including the
  successful one. Given `max-attempts` (10) failures, the row becomes `DEAD`,
  `CALLER_WEBHOOK_FAILED` is recorded, and `apiorch.outbox.dead` is incremented.
- [ ] **AC5:** Given an `X-Callback-Url` whose host is not in `apiorch.outbox.allowed-hosts`
  (exact or `*.suffix`), whose scheme is `http://` outside `local`, or whose host is an IP
  literal, when the run is started, then the response is `400` `validation-failed` and no run is
  created. Given an allowed host name that resolves at **send time** to a private, loopback or
  link-local address (DNS rebinding), the attempt is refused with `last_error =
  PRIVATE_ADDRESS` and retried as a failure.
- [ ] **AC6:** Given apiorch crashes after committing the status change and before dispatching,
  when any node's dispatcher runs, then the webhook is delivered.
- [ ] **AC7:** Given a dispatcher node that dies while sending, when `apiorch.outbox.claim-ms`
  passes, then another node re-claims the row and sends it. The receiver may see a duplicate
  with the same `X-Apiorch-Event-Id`. No row is sent concurrently by two nodes while a claim is
  valid.
- [ ] **AC8:** Given one receiver that takes 5 s per request, when 200 events for other receivers
  are pending, then their delivery is not delayed by it beyond one dispatch interval. Limits are
  `apiorch.outbox.max-concurrency` (16) overall and `per-host-concurrency` (4) per host.
- [ ] **AC11 (dead blocks the stream):** Given a run whose `run.pending_async` event reached `DEAD`
  and whose `run.completed` event is `PENDING`, when the dispatcher runs, then `run.completed` is
  **not** sent: it stays `PENDING` with `last_error = BLOCKED_BY_DEAD_PREDECESSOR`, no attempt is
  counted against it, and the run is counted in `apiorch.outbox.blocked_runs` with a WARN naming
  the run and the dead event. Redelivering the dead event (AC9) and succeeding then releases
  `run.completed`, and the receiver sees the two in the correct order. Events for **other** runs are
  unaffected.
- [ ] **AC9:** Given a `DEAD` event, when an operator calls
  `POST /api/v1/runs/{runId}/webhooks/{eventId}/redeliver` with a `reason`, then the row returns
  to `PENDING` with `attempts = 0`, `CALLER_WEBHOOK_REDELIVERED` `{actor, reason}` is recorded,
  and it is delivered on the next cycle.
- [ ] **AC10:** The notifiable events are `run.pending_async`, `run.completed`,
  `run.completed_with_errors`, `run.failed`, `run.timed_out`, `run.cancelled`, `run.compensated`
  and `run.compensation_failed`. `RUNNING`, `WAITING_CALLBACK` and `COMPENSATING` send nothing.
  Events whose statuses are delivered in R3 (US-5.6) are covered by the matrix test once those
  stories land.

## Technical details
**Components** (`com.crisil.apiorch.outbox`)
- `OutboxWriter.enqueue(run, eventType)` is called inside the status-change transaction by
  `RunFinalizer`, `QuiescenceEvaluator`, `CompensationRunner` and `RunOperatorService`. It is a
  no-op without `caller_webhook_url`. The payload is the run resource, encrypted with
  `ContextCipher` (US-7.6).
- `OutboxDispatcher` runs on every node every `apiorch.outbox.dispatch-interval-ms`. Each cycle:
  1. Select candidates: `status IN ('PENDING','SENDING')`, `next_attempt_at <= SYSUTCDATETIME()`, and
     (`status='PENDING'` or `claimed_until < SYSUTCDATETIME()`), with **no older row for the same run in
     `PENDING`, `SENDING` or `DEAD`**. Including `DEAD` in that predicate is what blocks the
     stream; excluding it would let later events overtake a dead one. A blocked row is marked with
     `last_error = BLOCKED_BY_DEAD_PREDECESSOR` the first time it is skipped, so the condition is
     visible in the data and not only in a gauge. Order by `created_at`, `TOP (100)`.
  2. **Claim** each row:
     ```sql
     UPDATE outbox_event SET status='SENDING', claimed_by=:me,
       claimed_until=DATEADD(MILLISECOND, :claimMs, SYSUTCDATETIME())
     WHERE id=:id AND (status='PENDING' OR (status='SENDING' AND claimed_until < SYSUTCDATETIME()))
     ```
     One row updated means this node owns it. This is portable SQL with no `SKIP LOCKED` and no
     SQL Server `READPAST` hint, so it runs unchanged on H2 and SQL Server. `:claimMs` is
     `apiorch.outbox.claim-ms`, bound as a long (ADR-003 rule 1).
  3. Send the claimed rows on a bounded pool, `max-concurrency` overall with a per-host
     semaphore, using a dedicated `RestClient` (connect 2 s, read 5 s, so it is far below
     `claim-ms`). The client uses a custom DNS check that rejects private, loopback and
     link-local addresses after resolution.
  4. On 2xx: `SENT`, `sent_at`, `attempts+1`. Otherwise: `PENDING`, `attempts+1`,
     `next_attempt_at = DATEADD(MILLISECOND, :backoffMs, SYSUTCDATETIME())`, where the
     backoff is `min(2^attempts s, 10 min) ± 20%` computed in Java, plus `last_http_status` and
     `last_error`. At `max-attempts` the row becomes `DEAD`.
- `WebhookSigner`: `t = epochSeconds`, `v1 = hex(HMAC-SHA256(key(apiorch.outbox.signing-key-ref),
  t + "." + body))`.
- `CallerWebhookUrlValidator` runs at run start (AC5).

**Data:** Flyway `V9__outbox_event.sql` creates `outbox_event` (README §10), including
`claimed_by` and `claimed_until`; `pipeline_run.caller_webhook_url`.

**Configuration:**
- `apiorch.outbox.signing-key-ref`, required when any allowed host is configured;
- `apiorch.outbox.allowed-hosts` (`[]` means webhooks are disabled);
- `apiorch.outbox.dispatch-interval-ms` (2000) and `apiorch.outbox.max-attempts` (10);
- `apiorch.outbox.max-concurrency` (16), `apiorch.outbox.per-host-concurrency` (4) and
  `apiorch.outbox.claim-ms` (60000).

**Metrics:** `apiorch.outbox.pending` (gauge), `apiorch.outbox.sent`, `apiorch.outbox.dead` and
`apiorch.outbox.blocked_runs` (gauge; alerted on in US-8.4, because a blocked stream means a caller
is silently not being told what happened to its transactions).

**Logging:** INFO `webhook sent event=run.completed attempts=1 httpStatus=200`; WARN `webhook
DEAD attempts=10`. URLs are logged without their query.

**Test notes**
- `CallerWebhookIT` covers the body, headers and signature, ordering, and retry to `SENT` and to
  `DEAD` (with a short backoff in the test profile) (AC1–AC4).
- `CallerWebhookUrlValidationTest` and `DnsRebindingIT` (resolver stub) cover AC5.
- `OutboxCrashIT` covers AC6.
- `OutboxReclaimIT` uses two contexts with node A killed mid-send (AC7). It is a claim test, so it
  is `slow`-tagged and runs on Testcontainers SQL Server (ADR-003 rule 6).
- `OutboxSlowReceiverIT` covers AC8.
- `OutboxDeadBlocksStreamIT` covers AC11, including that another run's events still flow.
- `RedeliverIT` covers AC9, and that redelivery releases a blocked successor in order.
- `WebhookEventMatrixTest` covers AC10.
