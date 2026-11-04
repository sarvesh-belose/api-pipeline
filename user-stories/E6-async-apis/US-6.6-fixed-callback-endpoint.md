# US-6.6 Fixed callback endpoint with correlation by job id
**Epic:** E6 Async steps · **Priority:** Could · **Size:** M · **Depends on:** US-6.2 · **Release:** R3

## User story
As a **Pipeline Administrator**, I want to integrate async APIs that can only call back to one
pre-registered URL, sign callbacks their own way, and identify the job by their own id, so that
those systems can be async steps too, without asking their owners to support per-request
callback URLs.

## Description
Many async APIs work differently from US-6.1:
- they call a **fixed URL** registered once;
- they sign callbacks with a **shared secret** of their own;
- they correlate by a **job id** returned in the acknowledgement.

This story adds a callback endpoint per apiorch endpoint, with the following parts:
- **Verification:** an `inboundCallback` section on the endpoint (US-2.1) defines the signature
  header, the algorithm (`HMAC_SHA256`), a secret reference, and an optional timestamp header
  with a tolerance.
- **Correlation:** the step declares `callback.correlateBy`, a JSONata expression over the
  callback body (for example `body.jobId`), matched against `callback.ackKey`, an expression
  over the ack (for example `ack.body.jobId`), stored when the ack arrives.
- **Processing:** after correlation, the callback is classified and applied exactly as in
  US-6.2: run lock first, `exec_no`, `wakeup_requested`, inbox and events.

The R2 async-API inventory decides whether this story is pulled into R2.

**Out of scope:** polling-based APIs and message brokers.

## Acceptance criteria
- [ ] **AC1:** Given endpoint `doc-svc` with `inboundCallback: {signatureHeader: "X-Doc-Signature",
  algorithm: "HMAC_SHA256", secretRef: "env:APIORCH_SECRET_DOC_CB"}` and step api4 with
  `callback: {mode: "FIXED_ENDPOINT", ackKey: "ack.body.jobId", correlateBy: "body.jobId",
  timeoutMs: 600000}`, when api4's ack returns `{"jobId":"J-1"}`, then the correlation key `J-1`
  is stored for (run, api4, exec_no).
- [ ] **AC2:** Given `POST /api/v1/callbacks/endpoints/doc-svc` with body `{"jobId":"J-1",
  "status":"DONE"}` and a valid `X-Doc-Signature`, when received, then it is matched to api4 of
  that run and processed as in US-6.2 AC1. The response is `200 {"status":"ACCEPTED"}`.
- [ ] **AC3:** Given an invalid or missing signature, or a timestamp outside tolerance, the
  response is `401` `invalid-callback-token`. A REJECTED metadata row is written only if
  correlation is possible.
- [ ] **AC4:** Given a correlation key that matches no open execution, the response is `200
  {"status":"LATE"}`, and an inbox row `LATE` without `run_id` is written (`endpoint_name` +
  key) for operator review.
- [ ] **AC5:** Given two open executions with the same key, when the endpoint's validation
  checks the ack, then the second ack fails its step with `CALLBACK_KEY_CONFLICT`, so
  correlation keys are always unique among open executions.

## Technical details
**API contract:** `POST /api/v1/callbacks/endpoints/{endpoint}` is served by the callback
security chain (US-1.2), with no JWT; the signature is verified per endpoint.

**DSL and endpoint additions:**
- Step `callback.mode`: `PER_REQUEST_URL` (default, US-6.1) or `FIXED_ENDPOINT`, plus
  `callback.ackKey` and `callback.correlateBy` (validated by US-3.2 when this feature is on).
- Endpoint `inboundCallback`: `signatureHeader`, `algorithm`, `secretRef`, and optionally
  `timestampHeader` and `toleranceSeconds`.

**Data:** a new migration (next free number) creates `callback_correlation(endpoint_name,
correlation_key, run_id, step_id, exec_no, created_at, PRIMARY KEY(endpoint_name,
correlation_key))`. It also adds `endpoint_name` and `correlation_key` to `callback_inbox`.
Rows are deleted when the step leaves `AWAITING_CALLBACK`.

**Components:** `callback.FixedEndpointCallbackController`, `callback.SignatureVerifier`,
`callback.CorrelationStore`; and `CallbackService.receive` (US-6.2), reused after correlation.

**Test notes**
- `FixedEndpointCallbackIT` covers ack key capture, a signed callback, a bad signature, an
  unknown key, and a duplicate key conflict (AC1–AC5).
