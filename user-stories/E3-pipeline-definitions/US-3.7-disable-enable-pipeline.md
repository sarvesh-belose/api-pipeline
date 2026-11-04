# US-3.7 Disable and re-enable a pipeline
**Epic:** E3 Pipeline definitions · **Priority:** Must · **Size:** S · **Depends on:** US-3.3 · **Release:** R2

## User story
As an **Operations Engineer** or **Pipeline Administrator**, I want to stop new runs of a
pipeline instantly, without deleting or republishing it, so that after a bad change or a
downstream incident I can contain the impact while the fix is prepared.

## Description
The `pipeline.enabled` flag is a kill switch for **new** runs. Disabling a pipeline does not
touch runs already in flight. Operators who need to stop those use cancel (US-5.7). New start
requests get `409` `pipeline-disabled`. Callbacks, resumes and recovery of existing runs
continue normally.

**Out of scope:** scheduled maintenance windows.

## Acceptance criteria
- [ ] **AC1:** Given `CUSTOMER_ONBOARD` is enabled, when an ops or admin user calls
  `POST /api/v1/admin/pipelines/CUSTOMER_ONBOARD/disable` with `{"reason":"INC-5012 KYC
  outage"}`, then `pipeline.enabled = 0` and `disabled_reason` are stored with `updated_by`, and
  the response is `200`.
- [ ] **AC2:** Given a disabled pipeline, when any node receives a start request, then within
  `apiorch.definition.latest-cache-ttl-ms` (5 s) the response is `409` `pipeline-disabled`, with
  `detail` containing the reason. No run row is created.
- [ ] **AC3:** Given runs in progress or waiting for callbacks when the pipeline is disabled,
  then they continue to completion. Callbacks, timeouts, resume and recovery are unaffected.
- [ ] **AC4:** Given `POST …/enable` with a reason, when called, then new runs are accepted again
  within 5 s. Both actions are recorded in an INFO log with the actor and reason.
- [ ] **AC5:** Given a call without `reason`, when disable or enable is requested, then the
  response is `400` `validation-failed`. `GET /api/v1/admin/pipelines` shows `enabled` and
  `disabledReason`.

## Technical details
**API contract:** `POST /api/v1/admin/pipelines/{code}/disable` and `…/enable` take
`{ "reason": "1–500 chars" }` and return `200 { code, enabled, disabledReason, updatedAt,
updatedBy }`. Roles: `apiorch.admin` or `apiorch.ops` (US-1.2).

**Components:**
- `definition.PipelineControlService` updates the `pipeline` row with optimistic `row_version`.
- `DefinitionService.latestVersion(code)` (US-3.3) returns `enabled` together with the version,
  cached with the same TTL.
- `RunService.start` (US-4.1) checks the flag before admission and insert.

**Metrics:** gauge `apiorch.pipelines.disabled`.

**Test notes**
- `PipelineDisableIT` covers disabling, a start rejected on two nodes within the TTL, an
  in-flight run completing, re-enabling, and the reason being required (AC1–AC5).
