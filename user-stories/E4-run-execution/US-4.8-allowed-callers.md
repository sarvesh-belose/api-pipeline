# US-4.8 Restrict which callers may start a pipeline
**Epic:** E4 Run execution · **Priority:** Must · **Size:** S · **Depends on:** US-4.1 · **Release:** R2

## User story
As a **Security Officer**, I want each pipeline to list the calling systems that may start it,
so that a system allowed to run its own onboarding flow can't trigger another system's
account-opening pipeline just because it holds the `apiorch.run` role.

## Description
The pipeline DSL field `allowedCallers` (US-3.1) lists principal names, the values of
`apiorch.security.principal-claim`, typically the client id. A start request from a principal
not on the list is treated as if the pipeline didn't exist, so pipeline names can't be
enumerated. The list is part of the **versioned** definition, so changes are published,
audited and rolled back like any other change. Operators (`apiorch.ops`) are not exempt from
starting runs this way. They use the operator APIs instead.

**Out of scope:** per-caller rate limits and per-step permissions.

## Acceptance criteria
- [ ] **AC1:** Given `CUSTOMER_ONBOARD` v5 with `"allowedCallers": ["crm-system"]`, when principal
  `crm-system` (role `apiorch.run`) starts a run, then it is accepted.
- [ ] **AC2:** Given principal `branch-portal` with role `apiorch.run`, when it starts the same
  pipeline, then the response is `404` `pipeline-not-found`, no run row is created, and a WARN
  log `caller not allowed pipeline=CUSTOMER_ONBOARD principal=branch-portal` is written.
- [ ] **AC3:** Given `"allowedCallers": []` or the field omitted, when any caller starts the
  pipeline, then it is rejected the same way (deny by default), and validation warns with
  `EMPTY_ALLOWED_CALLERS` (US-3.2).
- [ ] **AC4:** Given v6 adds `branch-portal` and is published, when `branch-portal` starts a run,
  then within `apiorch.definition.latest-cache-ttl-ms` it is accepted, and runs pinned to v5
  are unaffected.
- [ ] **AC5:** Given an `Idempotency-Key` replay by an allowed caller after the caller was
  removed from the latest version, when replayed, then the existing run is returned. A replay
  only reads; it doesn't start work.

## Technical details
**Components:** `run.CallerAuthorizer.check(compiledLatest, principal)` is called in
`RunService.start` after the replay lookup (US-4.7) and before admission. It uses the latest
version's `allowedCallers`, held in `CompiledPipeline`.

**Migration note:** pipelines published in R1, before this story, have no `allowedCallers`.
They are deny-by-default once this story ships, so the release notes require adding callers
before the R2 upgrade. An `AllowedCallersMigrationCheck` startup report lists the pipelines
with empty lists.

**Logging and metrics:** WARN on rejection; counter `apiorch.runs.rejected{reason="caller"}`.

**Test notes**
- `AllowedCallersIT` covers an allowed caller, a denied caller, an empty list, a publish that
  adds a caller, and a replay after removal (AC1–AC5).
