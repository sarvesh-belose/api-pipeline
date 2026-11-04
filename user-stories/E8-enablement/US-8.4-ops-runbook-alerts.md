# US-8.4 Operations runbook and alert pack
**Epic:** E8 Enablement · **Priority:** Must · **Size:** M · **Depends on:** US-7.5, US-5.8 · **Release:** R2

## User story
As an **Operations Engineer**, I want a runbook entry and a tested alert for every condition
that needs human action, each saying how to detect it, diagnose it and fix it with apiorch's
own APIs, so that incidents on the first customer release are handled quickly and the same way
every time.

## Description
This story delivers two things:
1. **`docs/runbook.md`** with one entry per condition. Each entry gives the symptom, the
   alert, a diagnosis (timeline, search and attempt APIs, or SQL for DBAs), the remediation
   (API calls with examples), and escalation.
2. **Prometheus alert rules** in `ops/alerts/apiorch-rules.yml`, unit-tested with
   `promtool test rules`, plus an optional Grafana dashboard JSON in `ops/dashboards/`.

Every ERROR log and every metric listed in US-7.5 AC6 must map to an entry.

**Conditions covered (minimum):**

| Condition | Alert (PromQL sketch) | Remediation |
|---|---|---|
| Circuit open | `resilience4j_circuitbreaker_state{state="open"} == 1` for 2 m | Check the downstream; `POST …/circuit/reset` with a reason after the fix |
| Step error rate high | `rate(apiorch_step_attempts_total{outcome=~"TIMEOUT|CONNECT_ERROR|IO_ERROR|HTTP_ERROR"}[5m])` above the per-endpoint baseline | Identify the endpoint; disable the pipeline (US-3.7) if needed |
| Unknown step outcomes | `increase(apiorch_recovery_steps_unknown_total[15m]) > 0` | Check the downstream; `POST …/steps/{id}/resolve`, then resume (US-5.7/5.8) |
| Lease lost spike | `increase(apiorch_lease_lost_total[5m]) > 5` | Check database latency and GC pauses |
| Stuck runs | `apiorch_retention_stuck_runs > 0`, or `RUNNING` older than 1 h | Inspect the timeline; cancel or resume |
| Webhook dead letters | `increase(apiorch_outbox_dead_total[1h]) > 0` | Contact the caller; `POST …/webhooks/{eventId}/redeliver` |
| Compensation failed | `increase(apiorch_compensation_failed_total[1h]) > 0` | Manual clean-up on the downstream, using the timeline's compensation entries |
| Callback timeouts spike | `rate(apiorch_callback_timeouts_total[15m])` above baseline | Check the async provider; use the inventory contacts (README Q5) |
| Admission rejections | `rate(apiorch_admission_rejected_total[5m]) > 0` for 10 m | Scale out or raise the limit after checking downstream capacity |
| Run latency SLO | p95 of `apiorch_run_duration_seconds` above the NFR | Compare downstream time vs overhead (US-8.2 method) |
| Retention job failing | No `apiorch_retention_duration` sample for 26 h | Check the ShedLock row and the job logs |
| Decryption failures | `CONTEXT_DECRYPTION_FAILED` errors in logs | Check key configuration and rotation (US-7.6) |
| **Webhook stream blocked** | `apiorch_outbox_blocked_runs > 0` for 15 m | A caller is silently not being told what happened: a `DEAD` event is holding later ones. Redeliver it, or abandon the stream explicitly (US-6.5 AC11) |
| **Missing partition** | `up == 1 and apiorch_readiness_reason{reason="missing-partition"} == 1`, or the open-ended last partition non-empty (`apiorch_retention_default_partition_rows > 0`; SQL Server has no default partition) | Run `PartitionMaintenanceJob` manually; it splits in the missing month boundaries (`NEXT USED` + `SPLIT RANGE`, ADR-003 F9), which moves those rows into their month partitions (US-7.8 AC8) |
| **Expression budget exceeded** | `increase(apiorch_expression_budget_exceeded_total[15m]) > 0` | A published expression is near or past its evaluation bound. Identify the pipeline and version from the event, and roll back (US-3.4) |
| **Payload access anomaly** | `increase(apiorch_payload_access_denied_total[1h]) > 10`, or an unusual rate of `PAYLOAD_ACCESSED` by one actor | Possible credential misuse. Review the actor's accesses through the timeline and escalate to the Security Officer (US-7.4 AC9) |

**Out of scope:** paging integrations, which the ops team configures in its alerting tool.

## Acceptance criteria
- [ ] **AC1:** Given `docs/runbook.md`, then it has an entry for every row in the table, and for
  every ERROR-level log message in the code base. A test greps `log.error(` messages and checks
  each has a runbook anchor.
- [ ] **AC2:** Given `ops/alerts/apiorch-rules.yml`, when `promtool check rules` and
  `promtool test rules ops/alerts/tests/*.yml` run in CI, then all rules are valid, and each
  rule has a unit test with firing and non-firing series.
- [ ] **AC3:** Given each runbook remediation that uses an apiorch API, then the entry contains a
  copy-paste `curl` example that works against the `local` demo (US-8.1). The examples are
  executed by a tagged IT.
- [ ] **AC4:** Given a game-day rehearsal using the US-8.1 demo (circuit open, unknown step,
  dead webhook), when an engineer who hasn't seen the code follows the runbook, then each
  incident is resolved within 15 minutes. Gaps found are fixed in the runbook.

## Technical details
**Files:** `docs/runbook.md`, `ops/alerts/apiorch-rules.yml`, `ops/alerts/tests/*.yml` and
`ops/dashboards/apiorch.json` (optional).

**CI:** a `promtool` container step in the build pipeline, plus `RunbookCoverageTest` (AC1).

**Test notes**
- `RunbookCoverageTest` covers AC1.
- The promtool tests cover AC2.
- `RunbookExamplesIT` is tagged `demo` (AC3).
- AC4 is a manual rehearsal, recorded in `docs/runbook.md#rehearsals`.
