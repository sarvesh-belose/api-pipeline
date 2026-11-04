# US-2.3 Audit history of endpoint changes
**Epic:** E2 Downstream endpoints · **Priority:** Should · **Size:** S · **Depends on:** US-2.1 · **Release:** R2

## User story
As a **Security Officer**, I want every change to a downstream endpoint (create, update,
delete, circuit reset) recorded with who did it, why, and the before and after values, so
that I can answer "who changed kyc-svc's base URL, and from what?" during an incident or audit.

## Description
Endpoint rows are updated in place (US-2.1), so their history must be kept separately. Every
change writes an `endpoint_audit` row in the same transaction as the change. Each row holds the
actor (JWT principal), the action, a required `reason` for destructive actions, and before and
after JSON snapshots. Auth sections hold **references only**, never resolved values. Pipeline
definitions don't need this table, because their history is the immutable version list
(US-3.3).

**Out of scope:** tamper-proof (WORM) storage.

## Acceptance criteria
- [ ] **AC1:** Given an admin updates `kyc-svc`'s `baseUrl` and `readTimeoutMs`, when the update
  commits, then an `endpoint_audit` row exists with `action=UPDATE`, the `actor`, `changed_at`,
  and `before_json` / `after_json` containing the full effective configuration. If the update
  rolls back, no audit row exists.
- [ ] **AC2:** Given `GET /api/v1/admin/endpoints/kyc-svc/history?page=0&size=20` by
  `apiorch.admin` or `apiorch.ops`, when called, then the entries are returned newest first. Each
  has `action`, `actor`, `reason`, `changedAt` and `changes[]`, a list of field-level
  differences such as `{"field":"baseUrl","from":"https://a…","to":"https://b…"}`.
- [ ] **AC3:** Given a `DELETE` of an endpoint, or a circuit reset (US-5.3), when requested
  without a `reason` (query parameter or body field, 1–500 characters), then the response is
  `400` `validation-failed`. With a reason, the audit row stores it. A deleted endpoint's
  history stays readable.
- [ ] **AC4:** Given auth changes, when audited, then `before_json` and `after_json` contain
  only types and references (for example `"secretRef":"env:APIORCH_SECRET_KYC"`), never resolved
  secret values.
- [ ] **AC5:** Given a `probe` call (US-2.1 AC6), when executed, then an audit row with
  `action=PROBE` and the result is written. This lets the security team see who tested which
  credentials against which host.

## Technical details
**API contract:** `GET /api/v1/admin/endpoints/{name}/history?page=&size=` (`size` ≤ 100):
```json
{ "items": [ { "action": "UPDATE", "actor": "a.sharma", "reason": null, "changedAt": "2026-11-04T10:15:02Z",
               "changes": [ { "field": "baseUrl", "from": "https://kyc-a.internal/api", "to": "https://kyc-b.internal/api" } ] } ],
  "page": 0, "size": 20, "totalElements": 7 }
```
A `DELETE /api/v1/admin/endpoints/{name}?reason=…` now requires `reason`.

**Data:** Flyway `V10__endpoint_audit.sql` creates `endpoint_audit` (README §10), with an index
on (`endpoint_name`, `changed_at`). Rows are deleted only by the retention job, after
`apiorch.retention.run-days` (US-7.8).

**Components** (`com.crisil.apiorch.endpoint`)
- `EndpointAuditWriter.record(action, name, before, after, reason)` has
  `@Transactional(propagation = MANDATORY)`. It is called by `EndpointService` and by
  `CircuitController` (US-5.3).
- `EndpointDiff` produces field-level changes from the two JSON snapshots (dotted paths).

**Test notes**
- `EndpointAuditIT` covers create, update and delete, rollback with no row, reason enforcement,
  reference-only snapshots, and the probe row (AC1, AC3–AC5).
- `EndpointHistoryApiIT` covers ordering, the diff, pagination and roles (AC2).
