# US-4.2 Start a run in caller-async mode and poll its status
**Epic:** E4 Run execution · **Priority:** Must · **Size:** S · **Depends on:** US-4.1 · **Release:** R1

## User story
As a **Calling System**, I want to start a run and get a run ID back immediately, then poll for
its status and result, so that long pipelines don't hold my connection open.

## Description
In **caller-async mode** (`mode=async` or `Prefer: respond-async`), the run is persisted exactly
as in US-4.1, and the API returns `202` right away. The caller then polls
`GET /api/v1/runs/{runId}`, or receives a webhook (US-6.5). This is unrelated to **async steps**
(E6), which are downstream APIs that call back later.

A caller with role `apiorch.run` can read **only runs it created**. Operators (`apiorch.ops`)
can read all runs.

**Out of scope:** caller webhooks (US-6.5) and the timeline and search APIs (US-7.4).

## Acceptance criteria
- [ ] **AC1:** Given a caller sends `POST /api/v1/pipelines/{code}/runs?mode=async`, when the
  run is accepted, then the response is `202` without waiting for any step, with
  `Location: /api/v1/runs/{runId}` and body `{runId, status: "ACCEPTED", pipeline, version,
  links}`. The run executes in the background. This is verified with a first step whose stub
  takes 5 s: the `202` arrives in under 1 s. The latency target itself is part of the NFR suite
  (US-8.2).
- [ ] **AC2:** Given `Prefer: respond-async` without a `mode` parameter, when the call is made,
  then it behaves like `mode=async`, and the response carries `Preference-Applied:
  respond-async`.
- [ ] **AC3:** Given a running run, when its creator calls `GET /api/v1/runs/{runId}`, then the
  response is `200` with the run resource (US-4.1) and its current `status`, and `Retry-After: 2`
  while the status is not done-for-caller. Once the run is done, `output`, `errors` and
  `endedAt`/`completedAt` are present and there is no `Retry-After`.
- [ ] **AC4:** Given a run created by principal `system-a`, when principal `system-b` (role
  `apiorch.run` only) requests it, then the response is `404` `run-not-found`, not 403, so run
  IDs can't be enumerated. An `apiorch.ops` principal gets `200`.
- [ ] **AC5:** Given a run ID that doesn't exist or isn't a UUID, when requested, then the
  response is `404` `run-not-found`.
- [ ] **AC6:** Given an async run that completes on node B after being started on node A, when
  the caller polls node A, then the latest persisted state is returned. State always comes from
  the database, never from in-memory caches.

## Technical details
**API contract**
- `POST /api/v1/pipelines/{code}/runs?mode=async` returns `202` with:
```json
{ "runId": "8d3f0c3e-…", "status": "ACCEPTED", "pipeline": "CUSTOMER_ONBOARD", "version": 3,
  "links": { "self": "/api/v1/runs/8d3f0c3e-…", "timeline": "/api/v1/runs/8d3f0c3e-…/timeline" } }
```
- `GET /api/v1/runs/{runId}` returns `200` with the run resource (US-4.1), or `404`.

**Components**
- `run.RunController.start` branches on mode. For async it returns `ResponseEntity.accepted()`
  immediately after the commit and does not register a completion future.
- `run.RunQueryService.get(runId, principal)` loads with `pipeline_run.created_by = principal`
  unless the principal has `ROLE_apiorch.ops`. Rows that aren't visible are treated as missing.
  Each read is a read-only transaction.
- `run.RunResourceAssembler` is shared with US-4.1.

**Data:** `pipeline_run.mode = 'ASYNC'`. The `created_by` index supports the ownership check.

**Logging:** INFO `run accepted mode=ASYNC`. Polls are logged at DEBUG to avoid log noise.

**Test notes**
- `AsyncRunIT` covers the 202 contract, `Prefer` handling, polling until `COMPLETED` (with
  Awaitility), and `Retry-After` presence and absence (AC1–AC3).
- `RunOwnershipIT` covers creator, another caller and ops principals (AC4).
- `RunNotFoundIT` covers AC5.
- `CrossNodePollIT` uses two contexts on one shared database (the same named H2 in-memory
  database in the fast tier) (AC6).
