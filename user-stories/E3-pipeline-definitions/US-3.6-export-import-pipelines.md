# US-3.6 Export and import pipelines between environments
**Epic:** E3 Pipeline definitions · **Priority:** Should · **Size:** M · **Depends on:** US-3.3 · **Release:** R2

## User story
As a **Pipeline Administrator**, I want to export a version that was tested in dev or UAT and
import it into production as a draft, with proof that the content is unchanged, so that
business changes are promoted through environments instead of being re-typed in production.

## Description
- **Export** returns a portable bundle: the definition, its checksum, the source environment and
  version, and the list of **required endpoint names**. Endpoints are environment-specific, so
  they are referenced by name and never exported with URLs or secrets.
- **Import** validates the bundle checksum, stores the definition as the target's **draft**, and
  records `source_checksum`.
- **Publish** then follows the normal US-3.3 flow, including four-eyes if enabled. The published
  version keeps `source_checksum`, so auditors can match production to the tested artefact.

**Out of scope:** automated promotion pipelines (CI/CD jobs can call these APIs) and endpoint
export.

## Acceptance criteria
- [ ] **AC1:** Given published `CUSTOMER_ONBOARD` v4 in UAT, when an admin calls
  `GET /api/v1/admin/pipelines/CUSTOMER_ONBOARD/versions/4/export`, then the response is `200`
  with `{ "format": "apiorch-pipeline/v1", "code", "version": 4, "checksum", "sourceEnvironment",
  "exportedAt", "exportedBy", "requiredEndpoints": ["customer-svc","kyc-svc",…], "definition" }`,
  and `Content-Disposition: attachment; filename=CUSTOMER_ONBOARD-v4.json`.
- [ ] **AC2:** Given that bundle, when an admin in production calls
  `POST /api/v1/admin/pipelines/import`, then the checksum is recomputed. If it matches, the
  definition becomes the draft of `CUSTOMER_ONBOARD`, created with a `pipeline` row if new, and
  the response is `201` with the US-3.2 validation result and `sourceChecksum`.
- [ ] **AC3:** Given a bundle whose `definition` was edited after export, so the checksum no
  longer matches, when imported, then the response is `422` `definition-invalid` with error
  `CHECKSUM_MISMATCH`, and nothing is stored.
- [ ] **AC4:** Given `requiredEndpoints` includes `score-svc`, which doesn't exist in
  production, when imported, then the draft is stored, and the validation result contains
  `UNKNOWN_ENDPOINT` for `score-svc`. Publishing is blocked until the endpoint is registered.
- [ ] **AC5:** Given an existing draft for the code, when importing, then `If-Match` on the
  existing draft is required (`428` without it, `409` if stale). This prevents an import from
  silently overwriting someone's work.
- [ ] **AC6:** Given the imported draft is published, when the versions are listed, then the new
  version shows `sourceChecksum` equal to the UAT checksum. Its own `checksum` is the same when
  the content is unchanged, which is the expected case.

## Technical details
**API contract**
- `GET /api/v1/admin/pipelines/{code}/versions/{v}/export` returns the bundle JSON
  (`apiorch.admin`).
- `POST /api/v1/admin/pipelines/import` takes the bundle as the body, plus an optional
  `If-Match`, and returns `201 { code, version: 0, status: "DRAFT", sourceChecksum, validation }`.

**Components** (`com.crisil.apiorch.definition`)
- `PipelineExportService` builds the bundle. `sourceEnvironment` comes from
  `spring.application.name` plus the active profile, and `requiredEndpoints` is the union of
  step and `compensate` endpoints.
- `PipelineImportService` recomputes the canonical checksum (US-3.3) and stores the draft with
  `source_checksum`.
- `pipeline_definition.source_checksum` is carried from draft to version on publish.

**Security:** the bundle contains no URLs or secrets, and import is subject to the same
validation and four-eyes rules as any draft.

**Logging:** INFO `pipeline exported` / `pipeline imported` with code, version, checksum and
actor.

**Test notes**
- `ExportImportIT` covers export, import into a second context (simulating production),
  checksum tamper, a missing endpoint, If-Match, and publish carrying `sourceChecksum`
  (AC1–AC6).
