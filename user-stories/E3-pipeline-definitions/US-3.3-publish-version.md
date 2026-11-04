# US-3.3 Publish a new version (including reordered steps) without redeploy
**Epic:** E3 Pipeline definitions · **Priority:** Must · **Size:** M · **Depends on:** US-3.2 · **Release:** R1

## User story
As a **Pipeline Administrator**, I want to publish my validated draft as a new immutable
version that new runs use within seconds, while runs already in flight continue on the version
they started with, so that business can change the API order (for example to
API1→API2→API5→API4→API3) without a release and without breaking work in progress.

## Description
Publishing does the following:
1. Validates the draft (US-3.2).
2. Stores it as version `N = latest + 1`, with a checksum and the publisher's identity.
3. Deletes the draft.

New runs always resolve the **latest** published version. Every run **pins** `(code, version)`
at start (US-4.1), so a publish never changes the steps, mappings or policies of a run in
flight. Endpoint settings are **not** pinned: they come from the endpoint registry at attempt
time (US-2.1).

An optional **four-eyes** mode requires the publisher to differ from the person who last saved
the draft, which lets a Business Owner approve changes. Published versions are compiled once
and cached, because they are immutable.

**Out of scope:** history, diff and rollback (US-3.4), export and import (US-3.6), and
disabling (US-3.7).

## Acceptance criteria
- [ ] **AC1:** Given a valid draft, when an admin sends
  `POST /api/v1/admin/pipelines/{code}/publish` with `{"comment":"Move API5 before API4 per
  business"}`, then the response is `201` with `Location: …/versions/{N}` and `{code, version: N,
  checksum, publishedAt, publishedBy, executionPlan}`, and the draft no longer exists.
- [ ] **AC2:** Given a draft with validation errors, when publish is called, then the response is
  `422` `definition-invalid` with the US-3.2 `errors[]`, and nothing is stored.
- [ ] **AC3 (reorder, no redeploy):** Given v1 `api1→api2→api3→api4→api5` is live and v2 with
  order `api1, api2, api5, api4, api3` is published, when a new run starts, then within
  `apiorch.definition.latest-cache-ttl-ms` (5 s), on every node, the run is pinned to v2 and its
  steps execute in v2 order. This is verified against WireMock request order, with no restart.
- [ ] **AC4 (in-flight safety):** Given a run on v1 that is paused (waiting for a callback or a
  slow step), when v2 is published, then that run continues with v1 steps, mappings and policies
  until it ends, and `GET /api/v1/runs/{id}` shows `"version": 1`.
- [ ] **AC5:** Given a draft whose canonical content equals the latest version, when publish is
  called, then the response is `409` `no-changes`. Given two concurrent publishes of the same
  code, exactly one succeeds, and the other gets `409` `version-conflict`, never `404`.
- [ ] **AC6:** Given `GET /api/v1/admin/pipelines/{code}/versions` by `apiorch.admin` or
  `apiorch.ops`, when called, then all versions are listed newest first with `version`,
  `checksum`, `publishedAt`, `publishedBy`, `comment`, `sourceVersion` and `sourceChecksum`.
  `GET …/versions/{v}` and `GET …/versions/latest` return the definition.
- [ ] **AC7:** Given `apiorch.definition.require-four-eyes=true`, when the principal who last
  saved the draft tries to publish it, then the response is `403` `forbidden` ("four-eyes: a
  different administrator must publish"). Another admin can publish, and both principals are
  recorded (`updated_by` on the draft, `published_by` on the version).
- [ ] **AC8:** Given a pipeline with no published version, when a run is started, then the
  response is `404` `no-published-version`.

## Technical details
**Publish algorithm (one transaction)**
1. Take the `SqlDialect` row lock on `pipeline` for `:code` (ADR-003 rule 4): SQL Server
   `SELECT … FROM pipeline WITH (UPDLOCK, ROWLOCK) WHERE code=:code`, H2
   `SELECT … FROM pipeline WHERE code=:code FOR UPDATE`. The wait is bounded by
   `SqlDialect.withLockTimeout(sql, apiorch.engine.lock-timeout-ms)`, which prefixes
   `SET LOCK_TIMEOUT` in the same statement on SQL Server (ADR-003 F5). This per-code lock row
   serializes concurrent publishes and rollbacks.
2. Load the draft. If it is missing and another version was created after this request started,
   return `409 version-conflict`; otherwise return `404`. Check four-eyes when enabled.
3. Run `DefinitionValidator.validate()` against the current endpoints.
4. **Stamp `dslVersion`** with `apiorch.definition.dsl-version` if the document omits it (US-3.1),
   so the stored content always states the revision it was written against. This happens **before**
   canonicalization, so the checksum covers it and two documents that differ only in DSL revision
   are not treated as `no-changes`.
5. Canonicalize: object keys sorted, **array order preserved**. Compute `checksum = SHA-256`.
6. If the checksum equals the latest version's, return `409 no-changes`.
7. `INSERT pipeline_definition(code, version = max+1, status = PUBLISHED, content, checksum,
   comment, published_at, published_by)`, then `DELETE` the draft, then publish
   `PipelinePublishedEvent` after commit.

**Resolution and caching** (`definition.DefinitionService`)
- `latestVersion(code)` uses a Caffeine cache with TTL `apiorch.definition.latest-cache-ttl-ms`
  (5 s). It is evicted locally on `PipelinePublishedEvent`, and other nodes converge within the
  TTL.
- `compiled(code, version)` uses a Caffeine cache (max 500 entries, no TTL) of
  `CompiledPipeline`. That object holds the parsed definition, compiled JSONata expressions,
  effective dependencies, ancestor and descendant sets, the plan, the post-completion set, and
  the **definition-derived** settings only. Endpoint-derived values (read timeout, retry,
  circuit-breaker and bulkhead defaults) are resolved at attempt time from `EndpointRuntime`, so
  endpoint changes apply without invalidating this cache (US-2.1 AC4).

**API contract**
- `POST …/{code}/publish` takes `{ "comment": "string ≤ 500" }`. Responses: `201`, `403`
  (four-eyes), `404` (no draft), `409` `no-changes` / `version-conflict`, `422`.
- `GET …/{code}/versions` and `GET …/{code}/versions/{v|latest}`.

**Configuration:** `apiorch.definition.latest-cache-ttl-ms` (5000),
`apiorch.definition.require-four-eyes` (false).

**Where four-eyes stops being this story's business.** `require-four-eyes` is a one-line check that
the publishing principal differs from the one who saved the draft. That is the whole of it, and it is
deliberately not an approval *workflow*. Publishing a reorder of production API calls is maker-checker,
so the first time this needs a checker queue, routing by product or region, approval limits, reminders,
escalation, send-back with comments, or a record of who approved what and why, it belongs to the
**platform workflow service** with a DMN table — not to a local approval chain in `DefinitionService`.
Build that as an integration story at that point; do not grow this flag into one.

**Logging:** INFO `pipeline published` with code, version, checksum, actor, comment and
`executionPlan`. The authoritative history is the `pipeline_definition` table.

**Test notes**
- `PublishApiIT` covers AC1, AC2, AC5, AC6 and AC8.
- `ConcurrentPublishIT` runs two threads that publish the same draft. It expects one 201 and
  one 409 (AC5). It is `slow`-tagged and runs on SQL Server, because a lock proven only on H2 is not
  evidence for SQL Server: H2 even parses the SQL Server table hint and ignores it (ADR-003 F3,
  rule 6).
- `ReorderNoRedeployIT` (headline) covers AC3.
- `InFlightPinningIT` covers AC4.
- `FourEyesIT` covers AC7.
