## Story
<!-- e.g. US-4.3 — link the story file under user-stories/ -->
Story: US-

## What changed
<!-- One or two sentences. Note any AC deliberately left for a follow-up story. -->

## Definition of Done (README §13)
- [ ] Every AC of the story is automated (unit tests for logic; integration tests with WireMock and H2 in `MODE=MSSQLServer`)
- [ ] `./mvnw verify` passes locally; coverage gates (line ≥ 80%, branch ≥ 85% on engine packages) hold
- [ ] ACs about row locking, leases, concurrent writers, partitions, grants, `ISJSON` or filtered indexes have `@Tag("slow")` tests on SQL Server (ADR-003 rule 6), and `./mvnw verify -Pslow` passes (or the nightly run is green)
- [ ] Database-specific SQL lives only in `SqlDialect`; migrations are SQL Server syntax in `common/`, with `sqlserver/` and `h2/` counterparts sharing a version number (ADR-003 rules 1–4)
- [ ] Migrations are expand/contract (README §10): no rename or drop in the release that stops using a column
- [ ] Time comes from the injected `Clock`; database time comes from `SYSUTCDATETIME()`
- [ ] No HTTP call inside a `@Transactional` method; `pipeline_run` written only through `JdbcClient`
- [ ] No secret or unmasked sensitive value in logs, API responses or test fixtures; no sensitive value in a log `{}` placeholder
- [ ] OpenAPI updated for any API change
- [ ] README §9–11 updated for any new endpoint, table or config key

## How it was tested
<!-- Test classes added or changed, and anything checked by hand. -->
