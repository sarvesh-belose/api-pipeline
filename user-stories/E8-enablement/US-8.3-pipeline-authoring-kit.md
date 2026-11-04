# US-8.3 Pipeline authoring kit
**Epic:** E8 Enablement · **Priority:** Must · **Size:** M · **Depends on:** US-3.2 · **Release:** R2

## User story
As a **Pipeline Administrator** (a technical integration analyst), I want editor autocomplete
and validation for pipeline JSON, recipes for the common changes, and a ready-made request
collection for draft → validate → publish → rollback, so that I can make business-requested
changes, such as a reorder, correctly and quickly without reading the source code.

## Description
apiorch has no UI. Pipelines are edited as JSON and managed through the admin API. This kit
makes that workable:
1. **JSON Schema** for the DSL, served at `GET /api/v1/admin/pipelines/schema`, and committed as
   `docs/schema/pipeline-v1.schema.json`. VS Code and IntelliJ use it for autocomplete and inline
   errors on `*.apiorch.json` files.
2. **Authoring guide** (`docs/authoring-guide.md`) with copy-paste recipes and the meaning of
   every validation code (US-3.2).
3. **Caller guide** (`docs/caller-guide.md`) for calling-system teams.
4. **Request collection** (`docs/admin-requests.http`) covering the admin lifecycle.
5. A **plain-language plan** in every validate response (US-3.2 `planDescription`).

The schema is hand-maintained, and a drift test checks it against the DSL records on every
build.

**Out of scope:** a graphical editor.

## Acceptance criteria
- [ ] **AC1:** Given `GET /api/v1/admin/pipelines/schema` (`apiorch.admin`), when called, then it
  returns the JSON Schema (draft 2020-12), with `description` on every field taken from the
  US-3.1 DSL reference, enums for `method`, `mode`, `onFailure` and `capture`, and patterns for
  `code` and step `id`.
- [ ] **AC2 (drift guard):** Given every fixture in `src/test/resources/definitions/valid/`, when
  the build runs, then each one validates against the schema **and** binds to the DSL records.
  Given each structural fixture in `…/invalid/`, both the schema and the binder reject it. A
  mismatch fails the build.
- [ ] **AC3:** Given a VS Code user opens `CUSTOMER_ONBOARD.apiorch.json` with the workspace
  settings from the kit, when they type a step, then the fields autocomplete, and an unknown
  field such as `dependOn` is underlined.
- [ ] **AC4:** Given the authoring guide, then it contains working recipes, each with the JSON
  and the expected `executionPlan`, for:
  - a reorder (including the 1→2→5→4→3 case and why a reorder can be rejected);
  - parallel steps;
  - a join;
  - mapping part of a field (`$substring`, array index, response header);
  - a non-blocking async step;
  - fallback;
  - continue-on-failure;
  - conditions;
  - compensation;
  - a version-pinned test run.

  Every recipe is also a test fixture that must validate.
- [ ] **AC5:** Given the caller guide, then it documents:
  - sync and caller-async modes;
  - the status contract (`200` with `status` and `X-Apiorch-Run-Status`, open question Q4);
  - `Idempotency-Key` semantics, including replaying a `FAILED` run (US-4.7);
  - `outputFinal`;
  - webhook signature verification, with a 15-line Java example (US-6.5);
  - error slugs.
- [ ] **AC6:** Given `docs/admin-requests.http` with variables for host and token, when executed
  in order against the `local` profile, then each request succeeds: create the draft, validate,
  publish, diff, roll back and disable.

## Technical details
**Components:** `definition.SchemaController` serves the schema file from the classpath, with
`Cache-Control: max-age=3600`.

**Files:** `docs/schema/pipeline-v1.schema.json`, `docs/authoring-guide.md`,
`docs/caller-guide.md`, `docs/admin-requests.http` and `.vscode/settings.json` (a
`json.schemas` mapping for `*.apiorch.json`).

**Test-only dependency:** a JSON Schema validator (for example `com.networknt:json-schema-validator`)
with `test` scope, used by `SchemaDriftTest`. The runtime never uses JSON Schema; the binder plus
US-3.2 are authoritative.

**Test notes**
- `SchemaDriftTest` covers AC2.
- `RecipeFixturesTest` validates every recipe in the guide, extracted from fenced blocks tagged
  `apiorch` (AC4).
- `AdminCollectionIT` runs the `.http` flow through a REST client (AC6).
- AC3 is verified manually.
