# US-3.4 View version history, diff and roll back
**Epic:** E3 Pipeline definitions · **Priority:** Should · **Size:** S · **Depends on:** US-3.3 · **Release:** R1

## User story
As a **Pipeline Administrator**, I want to see what changed between two versions, especially
the step order, and roll back to an earlier version in one call, so that I can review business
changes and undo a bad change quickly and safely.

## Description
The **semantic diff** compares two versions in business terms:
- whether the execution order or plan changed;
- which steps were added or removed;
- which fields changed per step;
- which pipeline-level settings changed.

**Rollback** to version k never rewrites history. It re-validates k's content against today's
endpoints and **republishes it as version N+1**, with `source_version = k`.

**Out of scope:** a text or line diff UI.

## Acceptance criteria
- [ ] **AC1:** Given v1 `api1…api5` and v2 `api1, api2, api5, api4, api3`, when an admin sends
  `GET /api/v1/admin/pipelines/{code}/versions/2/diff?against=1`, then the response contains
  `"orderChanged": true`, `"fromOrder": ["api1","api2","api3","api4","api5"]`,
  `"toOrder": ["api1","api2","api5","api4","api3"]`, the `fromPlan` and `toPlan` execution
  plans, empty `addedSteps` and `removedSteps`, and `changedSteps` for any step whose fields
  differ.
- [ ] **AC2:** Given v3 that changed `api2.retry.maxAttempts` from 3 to 5 and added step `api6`,
  when it is diffed against v2, then `changedSteps` contains
  `{"id":"api2","fields":[{"field":"retry.maxAttempts","from":3,"to":5}]}` and `addedSteps`
  contains `"api6"`.
- [ ] **AC3:** Given latest v3, when an admin sends `POST …/{code}/rollback?to=1` with
  `{"comment":"Revert reorder"}`, then v4 is created with v1's content,
  `sourceVersion: 1`, the new checksum and `publishedBy`. New runs use v4 within 5 s, and runs on
  v2 and v3 continue unchanged.
- [ ] **AC4:** Given v1 references an endpoint that has since been deleted or disabled, when
  rolling back to v1, then the response is `422` `definition-invalid` with the US-3.2 errors, and
  no version is created.
- [ ] **AC5:** Given `to` equal to the latest version, or a version that doesn't exist, when
  rolling back, then the response is `409` `no-changes` or `404` respectively. Rollback leaves
  any existing draft untouched.

## Technical details
**API contract**
- `GET /api/v1/admin/pipelines/{code}/versions/{v}/diff?against={v2}` returns:
```json
{
  "from": 1, "to": 2,
  "orderChanged": true,
  "fromOrder": ["api1","api2","api3","api4","api5"],
  "toOrder":   ["api1","api2","api5","api4","api3"],
  "fromPlan": [["api1"],["api2"],["api3"],["api4"],["api5"]],
  "toPlan":   [["api1"],["api2"],["api5"],["api4"],["api3"]],
  "addedSteps": [], "removedSteps": [],
  "changedSteps": [ { "id": "api4", "fields": [ { "field": "dependsOn(effective)", "from": ["api3"], "to": ["api5"] } ] } ],
  "changedSettings": [ { "field": "runTimeoutMs", "from": 30000, "to": 45000 } ]
}
```
- `POST /api/v1/admin/pipelines/{code}/rollback?to={k}` takes `{ "comment": "…" }` and returns
  `201` with the same response as publish (US-3.3).

**Components** (`com.crisil.apiorch.definition`)
- `DefinitionDiffService` compares two `CompiledPipeline` objects. Field comparison is a
  recursive walk over the canonical JSON of each step, keyed by step id, producing dotted field
  paths. **Effective `dependsOn`**, with implicit defaults applied, is diffed so that a pure
  reorder shows how dependencies changed. No external diff library is used.
- `RollbackService` takes the same per-code `pipeline` row lock as publish (US-3.3), loads
  version k, validates it (including the feature gate and post-completion rules of US-3.2), then
  reuses the US-3.3 publish routine with `source_version = k`. It does not delete the draft, and
  four-eyes doesn't apply, because rollback is an emergency action that is always logged with
  the actor.

**Logging:** rollback is logged at INFO with code, from version, the new version, actor and
comment.

**Test notes**
- `DefinitionDiffServiceTest` covers a pure reorder, a field change, an added or removed step,
  settings changes, and nested retry fields.
- `RollbackIT` covers a successful rollback with new runs on v4 and older runs untouched (AC3),
  an invalid rollback because of a deleted endpoint (AC4), and the no-changes and 404 cases (AC5).
