# S6: Re-estimate, team and review checkpoints (Sprint 0 review pack)

**Status:** prepared for the Sprint 0 review, not yet held. The whole team owns this.
**Inputs:** [R1 plan](../R1-implementation-plan.md) §2 (effort), §5 (workstreams), §8 (reviews),
§11 (team size); the story map in [user-stories/README.md](../../user-stories/README.md) §2.

## 1. Estimation sheet

Sizes in dev-days at the README midpoints (S ≈ 2, M ≈ 4.5, L ≈ 8). **Proposed** is the starting
point after the Sprint 0 gates. Planning poker fills **Agreed**.

| Story | Title | Size | Proposed | Why it changed | Agreed |
|---|---|---|---|---|---|
| US-1.1 | Project scaffold & runtime baseline | M | **L (8)** | ArchUnit rules with violating fixtures, three test tiers, `MigrationIT` with partition assertions, branch-coverage gate. Sprint 0 built the skeleton, the tiers and both coverage gates, which removes about 2 days | |
| US-1.2 | Secure APIs with SSO JWT and roles | M | 4.5 | | |
| US-1.3 | Correlation IDs and structured JSON logs | S | 2 | logstash 8.1 masking decorator already proven (ADR-003 F12) | |
| US-1.4 | Stable public address | S | 2 | Reworked 2026-11-05: own load balancer, forwarded headers, callback-URL guard; no gateway or registry | |
| US-2.1 | Register & manage downstream endpoints | M | 4.5 | | |
| US-2.2 | Downstream auth via secret references | M | 4.5 | | |
| US-3.1 | Author a pipeline draft in the JSON DSL | L | **L (8)** | G2 passed, so **not L+**: no restricted-evaluator fallback (ADR-002). The D3 built-in audit is in scope | |
| US-3.2 | Validate a pipeline before publishing | M | 4.5 | Adds the D4 pre-compile limits and the D6 regex ban (ADR-002) | |
| US-3.3 | Publish a new version without redeploy | M | 4.5 | | |
| US-3.4 | History, diff and rollback | S | 2 | | |
| US-4.1 | Start a run, synchronous result | M | 4.5 | | |
| US-4.2 | Caller-async mode and polling | S | 2 | | |
| US-4.3 | Sequential steps with mapping | L | 8 | | |
| US-4.4 | DAG and parallel steps | M | 4.5 | | |
| US-4.6 | Failure policies and output | M | 4.5 | | |
| US-4.7 | Inbound idempotency | S | 2 | | |
| US-5.1 | Step timeouts and run deadline | M | 4.5 | | |
| US-5.2 | Retry transient failures | M | 4.5 | | |
| US-5.3 | Circuit breaker per endpoint | M | 4.5 | | |
| US-5.4 | Bulkhead and admission control | S | 2 | | |
| US-5.5 | Crash recovery and graceful shutdown | L | 8 | Lock tests must run on SQL Server (`slow` tier): H2 ignores lock hints (ADR-003 F3) | |
| US-5.7 | Operator cancel and resolve | M | 4.5 | | |
| US-7.1 | Activity recorder and run event log | M | **M+ (6)** | Append-only grant model on SQL Server, monthly partitions on the shared `pf_month`/`ps_month` (ADR-003 F9, F10) | |
| US-7.2 | Call attempt details | M | 4.5 | | |
| US-7.3 | Capture level and masking | M | 4.5 | | |
| US-7.4 | Timeline and search API | M | **M+ (6)** | Partition pruning with plan assertions on SQL Server, `PAYLOAD_ACCESSED` events | |
| US-7.5 | Operational metrics | M | 4.5 | | |
| US-7.6 | Encrypted working context | M | 4.5 | | |
| US-7.7 | Caller exchange log | M | 4.5 | | |
| US-8.1 A | Sync reorder demo | ≈ S | 2 | | |
| | **Story total** | | **≈ 134.5** | Plan baseline was ≈ 129 | |

## 2. Capacity check

| Item | Dev-days |
|---|---|
| Story total (proposed) | ≈ 134.5 |
| Non-story work left after Sprint 0 (harnesses, environments, hardening; ≈ 20 planned, ≈ 5 used in Sprint 0) | ≈ 15 |
| **Demand** | **≈ 150** |
| Capacity: 4 developers × 6 sprints × 7 productive days | 168 |
| **Slack** | **≈ 18 (≈ 11 %)** |

The plan holds at 4 developers if poker lands within about +18 dev-days of the proposed figures.
Beyond that, apply R1 plan §11 (≈ 1 extra sprint per 28 dev-days) or trim US-3.4 / US-8.1 A with
the Business Owner (risk R8).

**Calendar note:** with Sprint 0 starting 9 Nov 2026, Sprint 3 runs over 14–25 Dec and Sprint 4
over 28 Dec–8 Jan. Agree leave plans now; a week of one developer's leave costs ≈ 3.5 dev-days.

## 3. Team confirmation

| Role | Workstream (plan §5) | Name | Confirmed |
|---|---|---|---|
| Dev A | Platform, edge and data protection | | ☐ |
| Dev B | Definitions and run API | | ☐ |
| Dev C | Engine (critical path) | | ☐ |
| Dev D | Endpoints, HTTP and resilience | | ☐ |
| QA/SDET (shared) | Harnesses, R1 end-to-end suite, exit verification | | ☐ |
| DevOps (part-time) | Developer machine setup (JDK 17, Docker), environments and their load balancers | | ☐ |
| DBA (part-time) | S3 database, logins, role mapping | | ☐ |
| Tech lead | ADR acceptance, review checkpoints | | ☐ |
| Business Owner | Regex ban (ADR-002 D6), reorder demo sign-off | | ☐ |

## 4. Architecture review checkpoints (plan §8), proposed dates

Dates assume Sprint 0 on 9–13 Nov 2026 and 2-week sprints after it. Shift them to the real start.

| Review | Proposed date | Entry condition | Attendees |
|---|---|---|---|
| Contracts and schema | Thu 26 Nov 2026 (end of S1) | Contracts merged into `main`; V2–V7 migrations green on both tiers | Tech lead, Dev C, Dev D, DBA |
| HTTP path | Fri 18 Dec 2026 (mid S3) | US-4.3 / US-7.2 draft branch | Tech lead, Dev C, Dev D |
| Concurrency | Mon 11 Jan 2027 (start of S5) | US-5.5 design note with sequence diagrams | Tech lead, Dev B, Dev C, QA |
| R1 security review | Thu 4 Feb 2027 (end of S6) | US-1.2, US-2.2, US-7.3, US-7.6 merged | Tech lead, Dev A, security reviewer |

## 5. Outcome (fill in at the review)

- Agreed story total: ____ dev-days. Plan change needed: yes / no.
- Team confirmed: yes / no. Gaps: ____
- Review dates accepted: yes / no.
