# G1/G2: ADR acceptance pack (Sprint 0 review)

**Status:** **reviewed 2026-11-05. Both ADRs accepted; D6 signed off.** The tech lead accepts; the
Business Owner signs off on one DSL restriction.
**Sprint 0 exit:** "ADR-003 (superseding ADR-001) and ADR-002 accepted" (R1 plan §3).

## What is being accepted

| ADR | Decision | Evidence | Decided by |
|---|---|---|---|
| [ADR-003](../adr/ADR-003-boot35-sqlserver.md) | Spring Boot 3.5.14 + Spring Cloud 2025.0.3 on JDK 17. SQL Server 2022 in production; H2 2.3 in `MODE=MSSQLServer` for local runs and the fast tier; Testcontainers SQL Server for the `slow` tier. Supersedes [ADR-001](../adr/ADR-001-stack.md) | Probe `tools/stack-probe-b35-mssql`: 24/24 (15 on H2, 9 on SQL Server). Walking skeleton: `verify` 9/9, `verify -Pslow` 12/12 | Tech lead |
| [ADR-002](../adr/ADR-002-expression-engine.md) | `com.dashjoin:jsonata` 0.9.10 behind `GuardedJsonataEvaluator` (rules D1–D7). No restricted-syntax fallback | Hostile suite: library bounds stop 7 of 10 cases; guards close the other 3 | Tech lead; D6 by the Business Owner |

## Decisions the review must make

| # | Question | Options | Recommendation |
|---|---|---|---|
| 1 | **Boot 3.5 has had no OSS patches since 2026-06-30** (ADR-003 F1, plan risk R1). How are CVEs after 3.5.14 handled? | (a) Buy commercial Spring support. (b) Bump within 3.5.x when a CVE needs it, re-running the stack probe each time. (c) Revisit the stack and move to Boot 4.x on SQL Server | (a) if production is planned before a Boot 4 move; otherwise (b), with a dated Boot 4 decision recorded as a follow-up ADR |
| 2 | **No regex literals in R1 expressions** (ADR-002 D6). US-3.2 rejects `/…/` with `REGEX_NOT_ALLOWED` | Accept for R1, or require regex and add a guarded matcher (new story, about M) | Accept for R1. `$substring`, `$contains` and `$split` with plain strings cover the US-8.3 recipes |
| 3 | **Double arithmetic** (ADR-002 D7): identifiers above 2^53 must not take part in arithmetic; money is computed downstream | Accept, or require exact decimal arithmetic | Accept; the DSL reference (US-3.1) states it |
| 4 | **What H2 proves** (ADR-003 rule 6): locking, concurrency, partitions, grants and `ISJSON` are proven only on SQL Server, in the nightly tier | Accept, or run the `slow` tier on every PR (+≈ 2 min per build) | Accept nightly; a red nightly blocks the sprint demo (plan §7.5) |

## Acceptance record

| ADR | Outcome | By | Date | Conditions |
|---|---|---|---|---|
| ADR-003 | **Accepted**, with decisions 1 and 4 | Tech lead (Recorded by Sarvesh Belose, 2026-11-05) | 2026-11-05 | Decision 1 as recommended: (a) commercial support if production comes before a Boot 4 move, otherwise (b) patch bumps within 3.5.x. Confirm the branch once the production date is set. Decision 4: SQL Server-only proofs run in the `slow` tier (amended 2026-11-05: locally before merge and before each sprint review; there is no CI) |
| ADR-002 | **Accepted**, with decision 3 | Tech lead (Recorded by Sarvesh Belose, 2026-11-05) | 2026-11-05 | Decision 3: double arithmetic accepted; the DSL reference (US-3.1) states the 2^53 limit |
| ADR-002 D6 (regex ban) | **Signed off** | Business Owner (Recorded by Sarvesh Belose, 2026-11-05) | 2026-11-05 | No regex literals in R1; revisit after R1 |

Each ADR's **Status** row is updated to the outcome (2026-11-05). Approver names were not given in the
review record; add them here if your process needs named approvers.
