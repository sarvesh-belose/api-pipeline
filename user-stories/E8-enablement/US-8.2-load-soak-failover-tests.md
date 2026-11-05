# US-8.2 Load, soak and failover tests against the NFRs
**Epic:** E8 Enablement · **Priority:** Must · **Size:** L · **Depends on:** US-6.3, US-5.4 · **Release:** R2

## User story
As the **Business Owner**, I want evidence that apiorch meets its throughput, latency, recovery
and storage targets under realistic load and failures before the first customer release, so
that we go live knowing how the system behaves and how much infrastructure it needs.

## Description
This story builds a repeatable performance suite and runs it against the NFRs in README §14. It
uses three apiorch nodes, SQL Server 2022 (the production database, never H2) and WireMock
downstreams with realistic latency distributions. The results go into `perf/REPORT.md`, and the measured storage per attempt
replaces the planning figure in README §14.

**Scenarios:**

| Scenario | Shape | Pass criteria |
|---|---|---|
| **Steady** | 100 runs/s for 30 min; 5 steps per run (one async step, non-blocking); `FULL` capture | p95 apiorch overhead ≤ 20 ms per step and ≤ 50 ms per run; error rate < 0.1%; no growth in `apiorch_runs_by_status{status="RUNNING"}` |
| **Soak** | 60 runs/s for 4 h | No memory growth trend (heap after GC within ±10%); no connection leaks; retention job and sweepers keep up |
| **Spike** | 0 → 300 runs/s for 2 min | `429 admission-rejected` used for shedding; no `5xx` from apiorch; recovery to steady within 1 min |
| **Node failover** | Kill one node (SIGKILL) at steady load | Every run started on it completes or ends in a defined status within 45 s (lease + sweep); 0 lost runs; 0 duplicate calls for non-idempotent steps (WireMock request journal) |
| **Downstream outage** | One endpoint 100% `503` for 2 min | Its circuit opens; p95 of other endpoints within +100 ms of baseline; runs follow their policies; recovery after the outage |
| **Callback storm** | 10,000 callbacks in 1 min, including 5% duplicates and 1% invalid tokens | All valid callbacks processed exactly once; duplicates `DUPLICATE`; invalid callbacks `401`, rate-limited; no stuck runs |

**Out of scope:** production capacity planning beyond the measured figures, and chaos
experiments on the database.

## Acceptance criteria
- [ ] **AC1:** Given `mvnw -Pperf verify` (or the documented compose command), when run, then the
  three-node environment starts, all scenarios execute, and a report is written to
  `perf/REPORT.md`. The report holds the percentiles, error rates, resource usage and pass/fail
  per criterion.
- [ ] **AC2:** Given the Steady and Spike scenarios, when they complete, then every pass
  criterion in the table holds. Any miss is recorded as a defect, with an owner, before the R2
  go-live.
- [ ] **AC3:** Given the Node failover scenario, when a node is killed, then the report shows the
  recovery time distribution (max ≤ 45 s), 0 lost runs, and 0 duplicate non-idempotent calls.
- [ ] **AC4:** Given the Steady scenario, then the report states the measured **bytes per
  attempt** and **bytes per run** at `FULL` and `METADATA` capture, plus rows per run per table.
  README §14 is updated with the derived daily and 30-day storage.
- [ ] **AC5:** Given the Soak and Callback storm scenarios, then their pass criteria hold, and
  the sweeper, outbox and retention metrics stay within their alert thresholds (US-8.4).

## Technical details
**Tooling**
- **Load generation:** Gatling Java DSL in Maven module `perf/`, run with
  `gatling-maven-plugin`.
- **Downstreams:** WireMock standalone, with `lognormal` delays: median 80 ms, p99 600 ms.
- **Environment:** `docker-compose.perf.yml` with 3 apiorch replicas behind an nginx round-robin,
  SQL Server 2022 (`mcr.microsoft.com/mssql/server:2022-latest`, with the `sqlserver/` migrations,
  so partitioning, grants and the dialect row lock are exercised; ADR-003) tuned for the test,
  Prometheus scraping every node, and a kill script for the
  failover scenario.

**Measurement**
- **apiorch overhead** = run wall time − Σ downstream durations, from `step_attempt` and the
  timeline API (US-7.4), cross-checked with Prometheus histograms.
- **Duplicates:** the WireMock request journal grouped by `Idempotency-Key`.

**Test notes**
- The suite runs on demand in a dedicated environment before each release, not in the everyday
  `./mvnw verify` build.
- Thresholds live in `perf/thresholds.yml`, so they can be tuned without code changes.
