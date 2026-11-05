# apiorch — API Orchestration Service: User Story Backlog

`apiorch` exposes **one inbound API call that orchestrates many downstream APIs**. Each
downstream API runs on its own infrastructure and can fail independently. Steps run in sequence
or in parallel, and later steps build their requests from fields of earlier responses. **Async
steps** call APIs that answer later through a callback, and a run can be reported complete while
those callbacks are still outstanding. Business owns the pipeline as **versioned data**, so
changing `API1→API2→API3→API4→API5` into `API1→API2→API5→API4→API3` is an admin publish, not a
deploy. Every activity is recorded and queryable: payloads, responses, call sequence, response
time and sizes.

This folder contains **user stories only**. Every story has a user story, a description,
acceptance criteria and technical details. This README holds the context the stories share, so
they don't repeat it. **Sections 5 and 7 to 11 are canonical:** if a story disagrees with them,
the README wins and the story is the one to fix.

**Terminology:**
- **Caller-async mode:** the *caller* gets `202` plus a run ID and polls or receives a webhook
  (US-4.2, US-6.5).
- **Async step:** a *downstream* API that acknowledges now and calls back later (US-6.x).

These are different features. Don't use "async" on its own.

---

## 1. Epics

| Epic | Folder | Goal |
|---|---|---|
| E1 Foundation | [E1-foundation](E1-foundation/) | Runnable, secured, observable service skeleton, reachable on a stable public address |
| E2 Downstream endpoints | [E2-endpoints](E2-endpoints/) | Register downstream APIs, their timeouts, resilience settings and auth, with change history |
| E3 Pipeline definitions | [E3-pipeline-definitions](E3-pipeline-definitions/) | Business-editable, validated, versioned pipelines that can be promoted and switched off |
| E4 Run execution | [E4-run-execution](E4-run-execution/) | Run a pipeline from one inbound call: sequence, mapping, DAG, policies, caller access |
| E5 Resilience | [E5-resilience](E5-resilience/) | Timeouts, retries, circuit breakers, bulkheads, recovery, compensation, operator repair |
| E6 Async steps | [E6-async-apis](E6-async-apis/) | Callback-based async steps, completion while pending, caller webhooks |
| E7 Activity logging & data protection | [E7-activity-logging](E7-activity-logging/) | Full activity log, masking, timeline, metrics, encryption, retention, erasure, tracing |
| E8 Enablement | [E8-enablement](E8-enablement/) | Demo, authoring kit, performance and failover proof, ops runbook, production database handover |

## 2. Story map

| ID | Title | Pri | Size | Depends on | Rel |
|---|---|---|---|---|---|
| [US-1.1](E1-foundation/US-1.1-project-scaffold.md) | Project scaffold & runtime baseline | Must | M | — | R1 |
| [US-1.2](E1-foundation/US-1.2-jwt-security.md) | Secure APIs with SSO JWT and roles | Must | M | US-1.1 | R1 |
| [US-1.3](E1-foundation/US-1.3-correlation-structured-logs.md) | Correlation IDs and structured JSON logs | Must | S | US-1.1 | R1 |
| [US-1.4](E1-foundation/US-1.4-stable-public-address.md) | Publish apiorch on a stable public address | Must | S | US-1.1 | R1 |
| [US-2.1](E2-endpoints/US-2.1-manage-endpoints.md) | Register & manage downstream endpoints | Must | M | US-1.2 | R1 |
| [US-2.2](E2-endpoints/US-2.2-downstream-auth-secret-refs.md) | Authenticate to downstream APIs via secret references | Must | M | US-2.1 | R1 |
| [US-2.3](E2-endpoints/US-2.3-endpoint-change-history.md) | Audit history of endpoint changes | Should | S | US-2.1 | R2 |
| [US-3.1](E3-pipeline-definitions/US-3.1-author-pipeline-draft.md) | Author a pipeline draft in the JSON DSL | Must | L | US-2.1 | R1 |
| [US-3.2](E3-pipeline-definitions/US-3.2-validate-pipeline.md) | Validate a pipeline before publishing | Must | M | US-3.1 | R1 |
| [US-3.3](E3-pipeline-definitions/US-3.3-publish-version.md) | Publish a new version (incl. reordered steps) without redeploy | Must | M | US-3.2 | R1 |
| [US-3.4](E3-pipeline-definitions/US-3.4-history-diff-rollback.md) | View version history, diff and roll back | Should | S | US-3.3 | R1 |
| [US-3.5](E3-pipeline-definitions/US-3.5-dry-run.md) | Dry-run a version with sample input and mocked responses | Should | M | US-3.3, US-4.3 | R3 |
| [US-3.6](E3-pipeline-definitions/US-3.6-export-import-pipelines.md) | Export and import pipelines between environments | Should | M | US-3.3 | R2 |
| [US-3.7](E3-pipeline-definitions/US-3.7-disable-enable-pipeline.md) | Disable and re-enable a pipeline | Must | S | US-3.3 | R2 |
| [US-4.1](E4-run-execution/US-4.1-start-run-sync.md) | Start a run and get the combined result synchronously | Must | M | US-3.3 | R1 |
| [US-4.2](E4-run-execution/US-4.2-start-run-async.md) | Start a run in caller-async mode and poll its status | Must | S | US-4.1 | R1 |
| [US-4.3](E4-run-execution/US-4.3-sequential-steps-mapping.md) | Execute sequential steps, building requests from earlier responses | Must | L | US-4.1, US-7.1 | R1 |
| [US-4.4](E4-run-execution/US-4.4-dag-parallel-steps.md) | Execute dependent and parallel steps (DAG) | Must | M | US-4.3 | R1 |
| [US-4.5](E4-run-execution/US-4.5-conditional-steps.md) | Skip steps conditionally | Should | S | US-4.3 | R3 |
| [US-4.6](E4-run-execution/US-4.6-failure-policies-output.md) | Apply per-step failure policies and assemble output | Must | M | US-4.3 | R1 |
| [US-4.7](E4-run-execution/US-4.7-inbound-idempotency.md) | De-duplicate caller retries with Idempotency-Key | Must | S | US-4.1 | R1 |
| [US-4.8](E4-run-execution/US-4.8-allowed-callers.md) | Restrict which callers may start a pipeline | Must | S | US-4.1 | R2 |
| [US-5.1](E5-resilience/US-5.1-timeouts-run-deadline.md) | Enforce step timeouts and the run deadline | Must | M | US-4.3 | R1 |
| [US-5.2](E5-resilience/US-5.2-retry-transient-failures.md) | Retry transient failures safely | Must | M | US-5.1, US-7.2 | R1 |
| [US-5.3](E5-resilience/US-5.3-circuit-breaker.md) | Circuit breaker per downstream endpoint | Must | M | US-2.1, US-5.2 | R1 |
| [US-5.4](E5-resilience/US-5.4-bulkhead-admission-control.md) | Bulkhead per endpoint and inbound admission control | Must | S | US-5.3, US-5.5 | R1 |
| [US-5.5](E5-resilience/US-5.5-crash-recovery-graceful-shutdown.md) | Recover runs after a node crash; graceful shutdown | Must | L | US-4.4 | R1 |
| [US-5.6](E5-resilience/US-5.6-compensation.md) | Compensate completed steps when a run fails | Should | M | US-4.6, US-5.1, US-5.5, US-6.1, US-7.2 | R3 |
| [US-5.7](E5-resilience/US-5.7-operator-cancel-resolve.md) | Operator cancel of a run and resolution of unknown steps | Must | M | US-4.6, US-5.5 | R1 |
| [US-5.8](E5-resilience/US-5.8-operator-resume.md) | Operator resume of a failed or timed-out run | Must | M | US-5.7 | R2 |
| [US-6.1](E6-async-apis/US-6.1-async-call-signed-callback.md) | Call an async step with a signed callback URL | Must | M | US-4.3, US-5.2 | R2 |
| [US-6.2](E6-async-apis/US-6.2-receive-verify-callbacks.md) | Receive and verify callbacks | Must | M | US-6.1, US-5.5 | R2 |
| [US-6.3](E6-async-apis/US-6.3-complete-while-async-pending.md) | Mark a run complete while async steps are pending; resume dependents | Must | L | US-6.2 | R2 |
| [US-6.4](E6-async-apis/US-6.4-callback-timeout.md) | Time out missing callbacks and apply the failure policy | Must | S | US-6.2 | R2 |
| [US-6.5](E6-async-apis/US-6.5-caller-webhook-outbox.md) | Notify the calling system via webhook (transactional outbox) | Must | M | US-4.2, US-6.3 | R2 |
| [US-6.6](E6-async-apis/US-6.6-fixed-callback-endpoint.md) | Fixed callback endpoint with correlation by job id | Could | M | US-6.2 | R3 |
| [US-7.1](E7-activity-logging/US-7.1-activity-sequence-log.md) | Activity recorder and run event log | Must | M | US-4.1 | R1 |
| [US-7.2](E7-activity-logging/US-7.2-call-attempt-details.md) | Record request, response, response time and sizes of every call attempt | Must | M | US-7.1, US-4.3 | R1 |
| [US-7.3](E7-activity-logging/US-7.3-capture-level-masking.md) | Configure capture level and mask sensitive data | Must | M | US-7.2 | R1 |
| [US-7.4](E7-activity-logging/US-7.4-timeline-search-api.md) | Query a run's timeline and search runs | Must | M | US-7.2, US-7.7 | R1 |
| [US-7.5](E7-activity-logging/US-7.5-metrics.md) | Operational metrics | Must | M | US-7.1, US-5.3 | R1 |
| [US-7.6](E7-activity-logging/US-7.6-encrypted-context.md) | Encrypt working context and webhook payloads | Must | M | US-4.3 | R1 |
| [US-7.7](E7-activity-logging/US-7.7-inbound-caller-exchange-log.md) | Log the caller's request and apiorch's response | Must | M | US-7.3 | R1 |
| [US-7.8](E7-activity-logging/US-7.8-data-retention.md) | Data retention | Must | M | US-7.2, US-7.6 | R2 |
| [US-7.9](E7-activity-logging/US-7.9-distributed-tracing.md) | Distributed tracing | Should | M | US-7.5 | R3 |
| [US-7.10](E7-activity-logging/US-7.10-erase-run-data.md) | Erase a run's personal data on request | Should | S | US-7.8 | R2 |
| [US-8.1](E8-enablement/US-8.1-local-demo-environment.md) | Local demo environment with stubbed APIs and reorder walkthrough | Must | M | US-4.6 / US-6.3 | R1 (A) / R2 (B) |
| [US-8.2](E8-enablement/US-8.2-load-soak-failover-tests.md) | Load, soak and failover tests against the NFRs | Must | L | US-6.3, US-5.4 | R2 |
| [US-8.3](E8-enablement/US-8.3-pipeline-authoring-kit.md) | Pipeline authoring kit | Must | M | US-3.2 | R2 |
| [US-8.4](E8-enablement/US-8.4-ops-runbook-alerts.md) | Operations runbook and alert pack | Must | M | US-7.5, US-5.8 | R2 |
| [US-8.5](E8-enablement/US-8.5-production-database-handover.md) | Hand over the production database to the DBA team | Must | M | US-7.1, US-7.8 | R2 |

**Sizes:** S ≈ 1–3 dev-days, M ≈ 3–6 dev-days, L ≈ 6–10 dev-days (including tests).

## 3. Release slices

| Release | Purpose | Scope | Exit criteria |
|---|---|---|---|
| **R1: internal milestone** | Prove the core engine; not offered to the customer | E1 (incl. US-1.4), US-2.1–2.2, US-3.1–3.4, US-4.1–4.4, US-4.6–4.7, US-5.1–5.5, US-5.7, US-7.1–7.7, US-8.1 slice A | Sync and caller-async runs, sequence and DAG with mapping, retries, timeouts, circuit breakers **and backpressure**, crash recovery, cancel and resolve, full activity log incl. caller exchange, metrics, encrypted working data, no-redeploy reorder and rollback, reachable on a stable public address, and a **runnable reorder demo** |
| **R2: first customer release** | Everything the customer asked for, production-ready | US-2.3, US-3.6–3.7, US-4.8, US-5.8, E6 (except US-6.6), US-7.8, US-7.10, US-8.1 slice B, US-8.2–8.5 | Async steps with complete-while-pending, callback timeouts, caller webhooks, resume, retention, erasure, promotion between environments, NFRs proven (US-8.2), runbook, production database handed to the DBA team (US-8.5) |
| **R3: enrichment** | Optional power features | US-3.5, US-4.5, US-5.6, US-6.6, US-7.9 | Dry run, conditions, compensation, fixed callback endpoints, tracing |

**R2 entry criterion:** an **inventory of the real async APIs** must exist before US-6.x starts.
For each one it records the callback style (per-request URL vs fixed registered URL), the
signature scheme, the correlation field and the expected callback latency. US-6.6 is pulled
into R2 if any async API needs a fixed URL.

**R1 partition caveat:** the R1 migrations create only the current and next months' partitions
(US-7.1). `PartitionMaintenanceJob` lands with US-7.8 in R2, so any R1 environment that runs for
more than two months starts writing into the default partition. R1 is an internal milestone, and
the default partition plus its alert keeps that visible rather than silent — but if R1 is kept
running, pull the creation half of US-7.8 forward.

**Feature gate:** DSL fields whose engine support ships in a later story are rejected at
publish with `FEATURE_NOT_AVAILABLE` until that story lands (US-3.2). The affected fields are
`mode: ASYNC_CALLBACK` / `callback` / `blocksRunCompletion` (US-6.1), `when` (US-4.5) and
`compensate` (US-5.6).

## 4. Traceability: requirement → stories

| Requirement or agreed decision | Stories |
|---|---|
| One API call orchestrates many APIs | US-4.1, US-4.3 |
| APIs run in sequence | US-4.3 |
| APIs depend on earlier APIs | US-4.4 |
| Next request uses (parts of) fields from earlier responses | US-3.1, US-4.3 |
| Async steps: don't wait to call the next API; mark the run complete while still waiting | US-6.1, US-6.2, US-6.3, US-6.4 |
| Log every activity: payload, response, call sequence, response time, payload/response size | US-7.1, US-7.2, US-7.3, US-7.4, US-7.7 |
| Order is configurable and changed by business (1→2→3→4→5 to 1→2→5→4→3) | US-3.1, US-3.2, US-3.3, US-3.4, US-8.3 |
| Reliable and resilient: each API on different infra may fail | US-4.6, US-4.7, US-5.1–US-5.8 |
| Decision: async downstream completion via callback/webhook | US-6.1, US-6.2, US-6.6 |
| Decision: caller sync mode and caller-async mode (202 + poll or webhook) | US-4.1, US-4.2, US-6.5 |
| Decision: versioned JSON config in DB via admin REST API, with rollback | US-3.1–US-3.4, US-3.6 |
| Decision: compensation is optional **per step** (a step may declare `compensate`) | US-5.6 |
| Decision: inbound auth via JWT from existing SSO, no user tables | US-1.2, US-4.8 |
| Edge: one stable public address per environment (own load balancer); no shared gateway or service registry | US-1.4 |
| No DBA team until production: database provisioning handed over as a story | US-8.5 |
| Platform: approvals beyond a one-line four-eyes check belong to the workflow service | US-3.3 |
| Non-functional targets (§14) | US-8.2, US-7.5, US-8.4 |
| Personal data protection | US-7.3, US-7.6, US-7.8, US-7.10 |

---

## 5. Architecture (shared context)

```
Calling System ─POST /api/v1/pipelines/{code}/runs─► RunController (MVC async, per-waiter CompletableFuture)
                                                         │ allowed callers · input checks · AdmissionControl
                                                         ▼
                                       RunCoordinator (event-driven, no thread per run)
                          pins (code, version) · fenced writes · computes ready set · persists every transition
                                                         │ submits ready steps (one level, never waits on itself)
                                                         ▼
                                 "step-io" ThreadPoolTaskExecutor (fixed size, JDK 17) — blocking HTTP only
                        ┌───────────────────────────────┼──────────────────────────────┐
                 HttpStepExecutor              AsyncCallbackStepExecutor          CompensationRunner
                        │ JSONata mapping over the run context
                        │ ResilientHttpClient per endpoint:
                        │   Retry → CircuitBreaker → Bulkhead(maxWait 0) → RestClient (JDK HttpClient)
                        ▼
                 Downstream APIs ──(async step)──► POST /api/v1/callbacks/{runId}/{stepId}?t=<token>
                        │
   ActivityRecorder → run_event · step_attempt · run_exchange (masked, size-capped) · JSON logs + MDC · Micrometer
   Background (DB time, cluster-safe): RecoverySweeper · CallbackTimeoutSweeper · OutboxDispatcher · RetentionJob
```

### 5.1 Concurrency rules (canonical, every story follows them)

1. **Lock order.** Any transaction that changes a run first locks its `pipeline_run` row with
   the **`SqlDialect` row lock**, then step rows. The lock is `SELECT … WITH (UPDLOCK, ROWLOCK)
   WHERE …` on SQL Server and `SELECT … WHERE … FOR UPDATE` on H2: H2 parses the SQL Server hint
   and ignores it, and SQL Server rejects `FOR UPDATE` (ADR-003 F3, F4). The wait is bounded by
   `apiorch.engine.lock-timeout-ms` through `SqlDialect.withLockTimeout` (ADR-003 F5). On deadlock
   (SQL Server error 1205) or lock timeout (error 1222), the transaction retries up to 3 times with
   jitter.
2. **Writes to `pipeline_run` go through `JdbcClient` conditional updates.** The JPA entity for
   `pipeline_run` is read-only (`@Immutable`), so a stale entity can never overwrite `event_seq`,
   `lease_until` or the flags.
3. **Fencing.** The node driving a run holds `(owner_instance, lease_epoch)`. Each of its write
   transactions locks the run row with `WHERE id=:id AND owner_instance=:me AND
   lease_epoch=:epoch`. If no row comes back, it throws `LeaseLostException`: the node drops the
   run locally, writes nothing, and discards late results.
4. **Wake-up.** Other writers make the run need a driver again, in a transaction that holds the
   run lock: callback receipt, callback timeout, operator actions, shutdown release, and failed
   start hand-off. Each one sets `wakeup_requested = 1`. The driving node clears it after
   re-reading the step states inside its own locked transaction. At quiescence, if the flag is
   set, the node keeps advancing instead of releasing the run.
5. **Claim predicate (the only one):** `(owner_instance IS NULL AND wakeup_requested = 1) OR
   (owner_instance IS NOT NULL AND lease_until < SYSUTCDATETIME())`. A claim sets
   `owner_instance=:me`, `lease_until = DATEADD(MILLISECOND, :leaseMs, SYSUTCDATETIME())`,
   `lease_epoch = lease_epoch + 1` and `wakeup_requested = 0`. All lease arithmetic uses
   **database time** (`SYSUTCDATETIME()`, UTC `DATETIME2(6)`), and durations are bound as `long`
   milliseconds inside `DATEADD(MILLISECOND, …)`. The same text runs on SQL Server and on H2
   (ADR-003 rules 1 and 3; probe `leaseClaimPredicateOnDatabaseTime`).
6. **Runs are created owned.** The insert sets `owner_instance`, `lease_until`, `lease_epoch = 1`,
   and writes `RUN_ACCEPTED` in the same transaction. If the hand-off to the coordinator fails,
   the run is released with `wakeup_requested = 1`.
7. **Stop intent is persisted** (`stop_reason`: `FAIL_RUN | DEADLINE | CANCEL`) in the same
   transaction as its cause. `advance` derives "stopping" from the database on every call.
8. **Notifications happen after commit.** Sync waiters, outbox dispatch and log lines that say
   "finished" are triggered `afterCommit`, never before.

### 5.2 Tech stack
- **Runtime:** JDK 17, Maven 3.9 (`mvnw`), **Spring Boot 3.5.14 with Spring Cloud 2025.0.3**,
  decided and verified at runtime by [ADR-003](../docs/adr/ADR-003-boot35-sqlserver.md), which
  supersedes ADR-001. The stack brings Spring Framework 6.2, Spring Security 6.5, Hibernate 6.6,
  Flyway 11.7 and Jackson 2.
  - **Support caveat (owner-accepted).** Boot 3.5's open-source support ended **2026-06-30**, so
    patches after 3.5.14 need a commercial subscription or a deliberate patch bump (ADR-003 F1).
  - Every pinned library version is listed in ADR-003, and changing one requires re-running
    [`tools/stack-probe-b35-mssql`](../tools/stack-probe-b35-mssql/).
- **Jackson:** Jackson 2 (`com.fasterxml.jackson`) is the only line on the classpath. Boot's
  auto-configured `ObjectMapper` **does not fail on unknown properties** (measured, ADR-003 F7),
  so strict binding is switched on explicitly (US-1.1). An ArchUnit rule bans `tools.jackson`
  imports, so a later Boot 4 upgrade is a deliberate change.
- **Starters:** web, validation, data-jpa, security, oauth2-resource-server and actuator, plus
  `flyway-core` + `flyway-sqlserver` (Boot 3.5 has no Flyway starter). `RestClient` comes from
  `spring-web`.
- **Database:** **SQL Server 2022 in production; H2 2.3 in `MODE=MSSQLServer` for the `local`
  profile and the fast test tier; real SQL Server (Testcontainers, `slow` tag) in the local
  `slow` tier** (ADR-003, README §13). The rules:
  - Migrations and engine SQL are written once, in SQL Server syntax (`UNIQUEIDENTIFIER`,
    `DATETIME2(6)` UTC, `BIT`, `NVARCHAR(MAX)`, `SYSUTCDATETIME()`, `DATEADD`, `TOP`,
    `OFFSET … FETCH`). Flyway reads `db/migration/common` plus `db/migration/{vendor}`.
  - H2 gets a shim migration for the one missing function, `SYSUTCDATETIME()`.
  - The only SQL that differs between the two databases is in `SqlDialect`: the row lock and the
    lock wait.
  - Locking, leases, concurrency, partitions, grants and `ISJSON` checks are **proven on SQL
    Server only** (ADR-003 rule 6). H2 silently ignores lock hints (F3), so an H2-only pass is not
    evidence for them.
  - Flyway owns the schema, and Hibernate runs with `ddl-auto: none`.
- **Libraries:** JSONata (`com.dashjoin:jsonata`); Resilience4j core modules (circuitbreaker,
  bulkhead, retry, micrometer), used programmatically; ShedLock (JDBC, DB time) for the
  callback-timeout sweeper and the retention job; Caffeine for local caches.
- **Observability:** logstash-logback-encoder, Micrometer Prometheus, OpenTelemetry bridge (R3).
- **API docs:** springdoc-openapi 2.8.x.
- **Tests:** JUnit 5, WireMock 3, Testcontainers 1.21 (`mssqlserver`), Awaitility,
  spring-security-test.

**Package root:** `com.crisil.apiorch`, with sub-packages `definition`, `endpoint`, `engine`,
`step`, `expression`, `http`, `callback`, `run`, `outbox`, `observability`, `security`,
`persistence` and `config`.

## 6. Glossary and personas

| Term | Meaning |
|---|---|
| **Pipeline** | A named orchestration (`code`, e.g. `CUSTOMER_ONBOARD`) defined in the JSON DSL. Its on/off switch lives in table `pipeline` |
| **Version** | Immutable published snapshot of a pipeline. Version `0` is the single editable draft |
| **Run** | One execution of a pipeline version, started by one inbound call |
| **Segment** | One active stretch of a run, bounded by `runTimeoutMs`. A new segment starts at start, after a callback or timeout wake-up from a waiting status, on operator resume, and when compensation begins |
| **Step** / **execution** | One downstream API call inside a run (`id` such as `api1`). `exec_no` counts its executions; a resume creates a new execution |
| **Attempt** | One HTTP exchange for a step execution. Retries create more attempts. `attempt_no` keeps increasing across executions |
| **Post-completion step** | A step that depends, directly or transitively, on an async step with `blocksRunCompletion: false`. It may run after the caller was told the run is complete |
| **Endpoint** | A registered downstream system: base URL, timeouts, resilience settings, auth. **Not** pinned per run; changes apply to the next attempt |
| **Lease** | A time-limited, fenced claim (`owner_instance`, `lease_epoch`, `lease_until`) of one node to drive a run |
| **Compensation** | An optional "undo" call for a succeeded step, made when the run fails |
| **Capture level** | How much of each exchange is stored: `METADATA`, `HEADERS` or `FULL` |

**Personas:**
- **Calling System:** the consumer of the single API.
- **Pipeline Administrator:** a *technical integration analyst* who edits the DSL using the
  authoring kit (US-8.3).
- **Business Owner:** decides and approves order changes, and may be the second pair of eyes
  under four-eyes publishing (US-3.3).
- **Operations Engineer**, **Security Officer** and **Developer**.
- **Database Administrator:** joins at the move to production (R2). Until then there is no DBA
  team; developers run SQL Server only through Testcontainers, and US-8.5 is the handover pack.

## 7. State models (canonical)

### Run status

**Never ask whether a status is "terminal".** The word hides three independent questions, and a
single `isTerminal()` guarantees that someone answers the wrong one. `RunStatus` exposes exactly
these three predicates instead, and no code derives one from another:

| Predicate | Question | Used by |
|---|---|---|
| `doneForCaller()` | May a sync waiter be answered and a webhook be sent? | US-4.1, US-6.5 |
| `active()` | May this run still start steps or be driven by a node? | the coordinator, admission control, the sweepers |
| `resumable()` | May an operator resume it into a new segment? | US-5.8 |

`FAILED` and `TIMED_OUT` are the states that make the three disagree: done for the caller, not
active, and still resumable. That combination is legitimate, and it is why `active()` is not simply
`!doneForCaller()`.

| Status | Meaning | Done for caller | Active | Resumable |
|---|---|---|---|---|
| `ACCEPTED` | Persisted and owned, not yet started | no | yes | no |
| `RUNNING` | Steps executing | no | yes | no |
| `WAITING_CALLBACK` | Nothing runnable; waiting for a callback from a step with `blocksRunCompletion=true` | no | yes | no |
| `COMPLETED_PENDING_ASYNC` | Complete for the caller; post-completion steps still outstanding | **yes** | yes | no |
| `COMPLETED` | All steps succeeded or were skipped by condition | yes | no | no |
| `COMPLETED_WITH_ERRORS` | Finished, but some step failed under `CONTINUE`, used `FALLBACK`, timed out its callback, or was cut off by the deadline in post-completion work | yes | no | no |
| `FAILED` | A `FAIL_RUN` step failed (or a step ended `UNKNOWN`) and there was nothing to compensate | yes | no | **yes** |
| `TIMED_OUT` | The segment deadline passed before completion and there was nothing to compensate | yes | no | **yes** |
| `COMPENSATING` | Running compensation calls | no | yes | no |
| `COMPENSATED` | All compensations succeeded | yes | no | no |
| `COMPENSATION_FAILED` | At least one compensation failed after retries | yes | no | no |
| `CANCELLED` | Cancelled by an operator | yes | no | no |

A run is eligible for retention's "terminal" rules when `!active()`; US-7.8's wording means exactly
that. `RunStatusPredicateTest` asserts all three columns for every status, so a new status cannot be
added without deciding each one.

**Transitions**
| From | To | Trigger |
|---|---|---|
| `ACCEPTED` | `RUNNING` | Coordinator starts the first segment |
| `ACCEPTED`, `RUNNING`, `WAITING_CALLBACK`, `COMPLETED_PENDING_ASYNC` | `CANCELLED` or `COMPENSATING` | Operator cancel (US-5.7); `COMPENSATING` when `compensate=true` and there are eligible steps |
| `RUNNING` | `WAITING_CALLBACK`, `COMPLETED_PENDING_ASYNC`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, `TIMED_OUT`, `COMPENSATING` | Quiescence, failure or deadline |
| `WAITING_CALLBACK` | `RUNNING` | Wake-up after a callback or timeout; a **new segment** starts |
| `WAITING_CALLBACK` | `FAILED` or `COMPENSATING` | A blocking async step fails under `FAIL_RUN` |
| `COMPLETED_PENDING_ASYNC` | `COMPLETED` or `COMPLETED_WITH_ERRORS` | All post-completion steps terminal. A wake-up starts a **new segment**; the status stays `COMPLETED_PENDING_ASYNC` while dependents run |
| `FAILED`, `TIMED_OUT` | `RUNNING` | Operator resume (US-5.8), the `resumable()` states. Allowed only when no step is `UNKNOWN` and the context isn't purged |
| `COMPENSATING` | `COMPENSATED` or `COMPENSATION_FAILED` | Compensation finished (own segment, `apiorch.compensation.deadline-ms`) |

A `COMPLETED_PENDING_ASYNC` run can **never** become `FAILED`, `TIMED_OUT` or `COMPENSATING`.
Validation guarantees that post-completion steps are `CONTINUE` or `FALLBACK` (US-3.2). A
deadline in post-completion work ends the run `COMPLETED_WITH_ERRORS`, with error `RUN_TIMEOUT`
(US-5.1).

### Step status
| Status | Meaning |
|---|---|
| `PENDING` | Not started in the current execution |
| `RUNNING` | Request in flight, or waiting for a retry |
| `SUCCEEDED` | 2xx and `successWhen` passed, a callback succeeded, or a fallback was applied (`fallback_applied=true`) |
| `FAILED` | Failed after retries; the failure policy has been applied |
| `SKIPPED` | Not executed. `skip_reason`: `CONDITION_FALSE` or `DEPENDENCY_FAILED` |
| `AWAITING_CALLBACK` | An async request was accepted; waiting for the callback |
| `CALLBACK_TIMED_OUT` | No callback arrived before `callback.timeoutMs` |
| `CANCELLED` | Not started, or aborted when it was **safe to resend** (idempotent), because the run stopped |
| `COMPENSATED` | Was `SUCCEEDED` or `AWAITING_CALLBACK`; its compensation call succeeded |
| `UNKNOWN` | A non-idempotent request may have reached the downstream, but its outcome is unknown (crash, deadline abort, cancel abort). Never re-sent automatically |

**Transitions:**
- `PENDING → RUNNING | SKIPPED | CANCELLED`
- `RUNNING → SUCCEEDED | FAILED | AWAITING_CALLBACK | CANCELLED (idempotent abort) | UNKNOWN (non-idempotent abort) | PENDING` (the last is recovery re-executing an idempotent step)
- `AWAITING_CALLBACK → SUCCEEDED | FAILED | CALLBACK_TIMED_OUT | CANCELLED | COMPENSATED`
- `SUCCEEDED → COMPENSATED`
- `UNKNOWN → SUCCEEDED | FAILED`, through operator resolve only (US-5.7)
- `FAILED | CANCELLED | CALLBACK_TIMED_OUT | SKIPPED(DEPENDENCY_FAILED) → PENDING`, through
  operator resume, which increments `exec_no` (US-5.8)

A step that finishes **after** the run started stopping keeps its real outcome. A success then
stays `SUCCEEDED`, so it can be compensated.

**Dependency rule:** a step is ready when every dependency is `SUCCEEDED` or
`SKIPPED(CONDITION_FALSE)`. If any dependency is `FAILED`, `CALLBACK_TIMED_OUT`, `UNKNOWN` or
`SKIPPED(DEPENDENCY_FAILED)`, the step becomes `SKIPPED(DEPENDENCY_FAILED)`.

## 8. Pipeline DSL summary (full reference in US-3.1)

```json
{
  "dslVersion": 1,
  "code": "CUSTOMER_ONBOARD",
  "description": "Onboard a retail customer",
  "allowedCallers": ["crm-system", "branch-portal"],
  "input": { "required": ["name", "pan"], "validWhen": "$length(pan) = 10" },
  "runTimeoutMs": 30000,
  "syncWaitMs": 10000,
  "logging": { "capture": "FULL", "maxBodyBytes": 262144, "maskKeys": ["pan", "aadhaar", "customer.dob"] },
  "defaults": { "timeoutMs": 5000, "retry": { "maxAttempts": 3, "backoff": "EXPONENTIAL_JITTER", "initialMs": 200, "maxMs": 2000 } },
  "steps": [
    { "id": "api1", "endpoint": "customer-svc", "method": "POST", "path": "/customers", "idempotent": true,
      "body": "{ 'name': input.name, 'pan': input.pan }",
      "compensate": { "method": "DELETE", "path": "/customers/{steps.api1.body.id}" } },
    { "id": "api2", "endpoint": "kyc-svc", "method": "POST", "path": "/kyc",
      "body": "{ 'customerId': steps.api1.body.id, 'branch': $substring(steps.api1.body.accountNo, 4, 3) }",
      "successWhen": "body.result != 'ERROR'" },
    { "id": "api5", "endpoint": "notify-svc", "method": "POST", "path": "/notifications",
      "mode": "ASYNC_CALLBACK", "dependsOn": ["api2"],
      "body": "{ 'customerId': steps.api1.body.id, 'replyTo': callback.url }",
      "callback": { "timeoutMs": 3600000, "successWhen": "body.status = 'DONE'" },
      "blocksRunCompletion": false, "onFailure": "CONTINUE" }
  ],
  "output": "{ 'customerId': steps.api1.body.id, 'kyc': steps.api2.body.status }"
}
```
- **`dslVersion`** states the DSL revision the document was written against. It is stamped at
  publish and read by the compiler, so a version published today still compiles after the DSL grows
  (US-3.1). Absent means `1`.
- **Order:** if `dependsOn` is omitted, the step depends on the previous step in the list.
  Reordering the list therefore reorders execution. `"dependsOn": []` makes a root step.
  Validation warns when list order and execution order differ (US-3.2).
- **Expressions** are JSONata strings evaluated over the run context:
  `input`; `steps.<id>.status | headers | body | ack`; `run.id | correlationId | pipeline | version`;
  `callback.url` (async steps only).
- `path` supports `{<expression>}` interpolation. Values are URL-encoded, and empty, `.` or `..`
  segments are rejected.
- **Numbers** (ADR-002 D7):
  - integers that are only passed through stay exact, even above 2^53;
  - arithmetic, `$number` and decimals are doubles, so identifiers must not take part in
    arithmetic, and money math belongs downstream.
- **Engine:** JSONata 0.9.10 behind `GuardedJsonataEvaluator` ([ADR-002](../docs/adr/ADR-002-expression-engine.md)):
  - pooled instances per expression;
  - a 1 s wall-clock and depth-200 bound on every evaluation;
  - capped built-ins;
  - length and nesting limits checked before compile;
  - **no regex literals** in R1.

## 9. API surface

Paths below are the public paths. Each environment publishes apiorch on one stable address in
front of all nodes (its own load balancer or ingress, US-1.4), with no path prefix — so
`POST /api/v1/pipelines/{code}/runs` is called as `POST {public-base-url}/api/v1/pipelines/{code}/runs`.
`apiorch.callback.public-base-url` must be that public address, because it is handed to external
systems.

| Method & path | Role | Story |
|---|---|---|
| `POST /api/v1/pipelines/{code}/runs?mode=sync\|async[&version=N]` | `apiorch.run` (+ allow-list, US-4.8); `version` needs `apiorch.admin` and `apiorch.run.allow-version-override` | US-4.1, US-4.2 |
| `GET /api/v1/runs/{runId}` | `apiorch.run` (own runs), `apiorch.ops` | US-4.2 |
| `GET /api/v1/runs/{runId}/timeline[?include=events]` | `apiorch.run` (own runs), `apiorch.ops` | US-7.4 |
| `GET /api/v1/runs/{runId}/steps/{stepId}/attempts/{n}?kind=REQUEST\|COMPENSATION` | `apiorch.ops` | US-7.4 |
| `GET /api/v1/runs/{runId}/steps/{stepId}/callbacks/{n}` | `apiorch.ops` | US-7.4 |
| `GET /api/v1/runs/{runId}/exchanges/{kind}` (`INBOUND_REQUEST\|CALLER_RESPONSE`) | `apiorch.ops` | US-7.7 |
| `GET /api/v1/runs?pipeline=&status=&from=&to=&correlationId=&page=&size=` | `apiorch.run` (own runs), `apiorch.ops` | US-7.4 |
| `POST /api/v1/runs/{runId}/cancel` · `POST /api/v1/runs/{runId}/steps/{stepId}/resolve` | `apiorch.ops` | US-5.7 |
| `POST /api/v1/runs/{runId}/resume` | `apiorch.ops` | US-5.8 |
| `POST /api/v1/runs/{runId}/webhooks/{eventId}/redeliver` | `apiorch.ops` | US-6.5 |
| `POST /api/v1/runs/erase` | `apiorch.ops` | US-7.10 |
| `POST, DELETE /api/v1/runs/{runId}/hold` | `apiorch.ops` | US-7.8 |
| `POST /api/v1/callbacks/{runId}/{stepId}?t=<token>` | callback token | US-6.2 |
| `POST /api/v1/callbacks/endpoints/{endpoint}` | per-endpoint HMAC | US-6.6 |
| `POST, GET /api/v1/admin/endpoints` · `GET, PUT, DELETE /api/v1/admin/endpoints/{name}` | `apiorch.admin` (GET also `apiorch.ops`) | US-2.1 |
| `POST /api/v1/admin/endpoints/{name}/probe` | `apiorch.admin` | US-2.1 |
| `GET /api/v1/admin/endpoints/{name}/history` | `apiorch.admin`, `apiorch.ops` | US-2.3 |
| `GET /api/v1/admin/endpoints/{name}/circuit` · `POST …/circuit/reset` | `apiorch.ops` | US-5.3 |
| `POST, GET /api/v1/admin/pipelines` · `GET, PUT, DELETE /api/v1/admin/pipelines/{code}/draft` | `apiorch.admin` (GET also `apiorch.ops`) | US-3.1 |
| `GET /api/v1/admin/pipelines/schema` | `apiorch.admin` | US-8.3 |
| `POST /api/v1/admin/pipelines/{code}/validate` | `apiorch.admin` | US-3.2 |
| `POST /api/v1/admin/pipelines/{code}/publish` · `GET …/{code}/versions` · `GET …/{code}/versions/{v\|latest}` | `apiorch.admin` (GET also `apiorch.ops`) | US-3.3 |
| `GET …/{code}/versions/{v}/diff?against={v2}` · `POST …/{code}/rollback?to={k}` | `apiorch.admin` | US-3.4 |
| `POST …/{code}/versions/{v}/dry-run` · `POST …/{code}/draft/dry-run` | `apiorch.admin` | US-3.5 |
| `GET …/{code}/versions/{v}/export` · `POST /api/v1/admin/pipelines/import` | `apiorch.admin` | US-3.6 |
| `POST …/{code}/disable` · `POST …/{code}/enable` | `apiorch.admin`, `apiorch.ops` | US-3.7 |
| `GET /actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` | management port 9091, own security chain | US-1.1, US-1.2, US-7.5 |

**Headers:**
| Header | Direction | Purpose |
|---|---|---|
| `X-Correlation-Id` | in / out | Correlates a business transaction across systems (US-1.3) |
| `Idempotency-Key` | in | Caller retry de-duplication, unique per pipeline **and caller** (US-4.7) |
| `Idempotency-Key` | out | `{runId}:{stepId}:{execNo}`, so downstream can de-duplicate our retries (US-5.2) |
| `X-Callback-Url` | in | Optional caller webhook for run notifications (US-6.5) |
| `X-Callback-Url` | out | Callback URL with the signed token, sent to async steps (US-6.1) |
| `X-Callback-Token` | in | Alternative to the `?t=` callback token query parameter (US-6.2) |
| `X-Apiorch-Run-Id`, `X-Apiorch-Step-Id` | out | Run and step identity for downstream logs |
| `X-Apiorch-Run-Status` | response | Run status, so callers can branch without parsing the body (US-4.1) |
| `traceparent` | out | W3C trace context (US-7.9) |
| `X-Signature`, `X-Apiorch-Event`, `X-Apiorch-Event-Id` | out | Caller webhook signature, event type and de-duplication id (US-6.5) |
| `Location`, `Retry-After`, `Idempotent-Replayed`, `Preference-Applied` | response | Run location, back-off hint, idempotent replay flag, `respond-async` confirmation |

**Errors** are RFC 9457 `application/problem+json`, with `type = urn:apiorch:problem:<slug>`:

| Slug | HTTP |
|---|---|
| `validation-failed` | 400 |
| `unauthorized` | 401 |
| `invalid-callback-token` | 401 |
| `forbidden` | 403 |
| `pipeline-not-found` | 404 |
| `no-published-version` | 404 |
| `run-not-found` | 404 |
| `run-on-legal-hold` | 409 |
| `endpoint-not-found` | 404 |
| `not-found` | 404 |
| `method-not-allowed` | 405 |
| `not-acceptable` | 406 |
| `already-exists` | 409 |
| `endpoint-in-use` | 409 |
| `version-conflict` | 409 |
| `no-changes` | 409 |
| `illegal-run-state` | 409 |
| `unresolved-steps` | 409 |
| `context-purged` | 409 |
| `pipeline-disabled` | 409 |
| `callback-not-expected` | 409 |
| `payload-too-large` | 413 |
| `unsupported-media-type` | 415 |
| `definition-invalid` | 422 |
| `idempotency-key-reuse` | 422 |
| `precondition-required` | 428 |
| `admission-rejected` | 429 |
| `internal-error` | 500 |
| `shutting-down` | 503 |

## 10. Data model (SQL Server 2022; H2 2.3 MSSQL mode for dev and test)

**Conventions** (ADR-003 rules 1–5):
- Ids are `UNIQUEIDENTIFIER`. Flags are `BIT`, compared with `= 1` and `= 0`.
- Timestamps are `DATETIME2(6)` holding **UTC**, defaulted with `SYSUTCDATETIME()`. Hibernate
  runs with `hibernate.jdbc.time_zone=UTC` **and**
  `hibernate.type.preferred_instant_jdbc_type=TIMESTAMP`. Without the second setting, `Instant`
  values read back shifted by the JVM's zone offset (ADR-003 F6).
- Text payloads and JSON documents are `NVARCHAR(MAX)`. JSON columns carry an `ISJSON` check in
  the `sqlserver/` migrations, and they are **never queried inside**: H2 has no `JSON_VALUE`
  (ADR-003 F15). Any field a story filters on gets its own column at write time, as `event_type`
  and `step_id` already do on `run_event`.
- Nullable unique keys, such as `pipeline_run.idempotency_key`, are vendor migrations. SQL Server
  uses a filtered unique index (`WHERE … IS NOT NULL`), because it allows only one NULL in a
  unique index; H2 uses a plain unique index (ADR-003 F16).
- Migrations live in `db/migration/common` (shared DDL) and `db/migration/{vendor}`
  (`sqlserver/`: partitioning, `ISJSON`, roles, grants, filtered indexes; `h2/`: plain
  equivalents plus the `SYSUTCDATETIME()` shim). Version numbers are global, and a vendor file
  and its counterpart share one.
- Hibernate uses `ddl-auto: none`. `MigrationIT` (US-1.1) proves every migration applies to an
  empty database and that each JPA entity round-trips.
- **The migration number follows delivery order.** A story delivered out of the planned order
  takes the next free number.
- **Every migration is expand/contract**, so a rolling restart always has the old and the new
  version running against one schema: add nullable columns and new tables first, backfill, switch
  the code, and drop in a later release. Never rename or drop a column in the same release that
  stops using it.

**Partitioning (the four high-volume tables):** `run_event`, `step_attempt`, `run_exchange` and
`callback_inbox` are **partitioned by month on SQL Server** on their own timestamp column
(`occurred_at`, `started_at`, `occurred_at`, `received_at`). Each uses a `RANGE RIGHT` partition
function on `DATETIME2(6)` and a partition scheme, with boundaries created from
`SYSUTCDATETIME()` (ADR-003 F9). At the NFR load these tables take billions of rows, and
row-by-row `DELETE` at 180 days would generate more transaction log than the write path itself.
Retention therefore **switches out whole months**: `TRUNCATE TABLE … WITH (PARTITIONS (n))`
followed by `MERGE RANGE` (US-7.8). H2 has no partitioning, so on H2 the same tables are plain
and retention falls back to batched `DELETE TOP (n)`. The partition path is proven only in the
`slow` tier.
- SQL Server requires the partitioning column in the clustered index and in every aligned unique
  index, so the primary keys carry it:
  `run_event(run_id, seq, occurred_at)`, `step_attempt(id, started_at)`. Gap-free `seq` per run is
  guaranteed by the counter allocated under the run row lock (US-7.1), not by the index; the index
  is the lookup path and a within-partition backstop.
- A query by `run_id` alone cannot prune partitions. Every read path therefore also passes a time
  bound derived from `pipeline_run.created_at` and `ended_at`, which the run row already supplies
  (US-7.4).
- `PartitionMaintenanceJob` (US-7.8) adds the next months' boundaries ahead of time, with
  `ALTER PARTITION SCHEME … NEXT USED` + `ALTER PARTITION FUNCTION … SPLIT RANGE`. It fails
  readiness if the current month has no boundary of its own.

SQL Server has no default partition. With `RANGE RIGHT`, the **first** partition holds rows
before the oldest boundary and the **last** holds rows past the newest one. Both are expected to
stay empty, and an alert fires if either is ever non-empty.

| Table | Key columns | Created by |
|---|---|---|
| `shedlock` | `name` PK, `lock_until`, `locked_at`, `locked_by` | US-1.1 `V1__baseline.sql` |
| `endpoint` | `id` PK, `name` UNIQUE, `description`, `base_url`, `health_path`, `connect_timeout_ms`, `read_timeout_ms`, `http_version`, `auth_type`, `auth_config`, `resilience_config`, `enabled`, `row_version`, `created_at/by`, `updated_at/by` | US-2.1 `V2__endpoint.sql` |
| `pipeline` | `code` PK, `description`, `enabled`, `disabled_reason`, `row_version`, `created_at/by`, `updated_at/by` | US-3.1 `V3__pipeline.sql` |
| `pipeline_definition` | `id` PK, `code` FK, `version` (0 = draft), `status` (`DRAFT\|PUBLISHED`), `content`, `checksum`, `comment`, `source_version`, `source_checksum`, `row_version`, `created_at/by`, `updated_at/by`, `published_at/by`; UNIQUE(`code`,`version`) | US-3.1 `V3__pipeline.sql` |
| `pipeline_run` | `id` PK, `pipeline_code`, `pipeline_version`, `status`, `mode`, `correlation_id`, `idempotency_key`, `request_hash`, `caller_webhook_url`, `created_by`, `created_at`, `started_at`, `segment_started_at`, `deadline_at`, `completed_at`, `ended_at`, `error_code`, `error_message`, `error_step_id`, `stop_reason`, `compensate_on_stop`, `event_seq`, `resume_count`, `owner_instance`, `lease_epoch`, `lease_until`, `wakeup_requested`, `context_purged_at`, `legal_hold`; UNIQUE(`pipeline_code`,`created_by`,`idempotency_key`) (filtered `WHERE idempotency_key IS NOT NULL` on SQL Server, ADR-003 F16); idx(`owner_instance`,`lease_until`), (`wakeup_requested`), (`pipeline_code`,`created_at`), (`correlation_id`), (`created_by`,`created_at`), (`status`,`ended_at`) | US-4.1 `V4__run.sql` |
| `step_execution` | `id` PK, `run_id`, `step_id`, `exec_no`, `status`, `skip_reason`, `mode`, `attempts`, `http_status`, `fallback_applied`, `started_at`, `ended_at`, `duration_ms`, `error_code`, `error_message`, `callback_deadline_at`, `callback_kid`, `completion_seq`, `compensation_status`, `row_version`; UNIQUE(`run_id`,`step_id`); idx(`status`,`callback_deadline_at`) | US-4.1 `V4__run.sql` |
| `run_context` | PK(`run_id`,`entry_key`), `content` (encrypted, US-7.6), `key_id`, `created_at`, `updated_at`. Entry keys: `input`, `<stepId>`, `output` | US-4.1 `V4__run.sql` |
| `run_event` | PK(`run_id`,`seq`,`occurred_at`), `event_type`, `step_id`, `exec_no`, `attempt`, `occurred_at`, `instance_id`, `actor`, `details` (`NVARCHAR(MAX)`, `ISJSON` check, not queried); **partitioned by month on `occurred_at`** | US-7.1 `V5__run_event.sql` |
| `step_attempt` | `id` PK, `run_id`, `step_id`, `exec_no`, `attempt_no`, `kind` (`REQUEST\|COMPENSATION`), `event_seq`, `endpoint_name`, `method`, `url`, `request_headers`, `request_body`, `request_bytes`, `request_truncated`, `response_status`, `response_headers`, `response_body`, `response_bytes`, `response_truncated`, `outcome`, `error_class`, `error_message`, `circuit_state`, `next_retry_delay_ms`, `started_at`, `ended_at`, `duration_ms`, `payload_purged`; PK(`id`,`started_at`), UNIQUE(`run_id`,`step_id`,`kind`,`attempt_no`,`started_at`); **partitioned by month on `started_at`** | US-7.2 `V6__step_attempt.sql` |
| `run_exchange` | `id` PK, `run_id`, `kind` (`INBOUND_REQUEST\|CALLER_RESPONSE`), `event_seq`, `http_status`, `headers`, `body`, `bytes`, `truncated`, `final`, `occurred_at`, `payload_purged`; PK(`id`,`occurred_at`), idx(`run_id`,`event_seq`); **partitioned by month on `occurred_at`** | US-7.7 `V7__run_exchange.sql` |
| `callback_inbox` | `id` PK, `run_id`, `step_id`, `exec_no`, `event_seq`, `received_at`, `status` (`ACCEPTED\|DUPLICATE\|LATE\|REJECTED`), `headers`, `body`, `body_bytes`, `body_truncated`, `source_ip`, `token_kid`, `payload_purged`; PK(`id`,`received_at`), idx(`run_id`,`step_id`,`exec_no`); **partitioned by month on `received_at`** | US-6.2 `V8__callback_inbox.sql` |
| `outbox_event` | `id` PK, `run_id`, `event_type`, `target_url`, `payload` (encrypted, US-7.6), `status` (`PENDING\|SENDING\|SENT\|DEAD`), `attempts`, `next_attempt_at`, `claimed_by`, `claimed_until`, `last_http_status`, `last_error`, `created_at`, `sent_at`; idx(`status`,`next_attempt_at`), (`run_id`,`created_at`) | US-6.5 `V9__outbox_event.sql` |
| `endpoint_audit` | `id` PK, `endpoint_name`, `action` (`CREATE\|UPDATE\|DELETE\|CIRCUIT_RESET\|PROBE`), `actor`, `reason`, `before_json`, `after_json`, `changed_at` | US-2.3 `V10__endpoint_audit.sql` |

`V4__run.sql` creates every run and step column up front, including the ones used later by
US-5.x and US-6.x.

**Attempt outcomes:** `SUCCESS`, `HTTP_ERROR`, `SUCCESS_CHECK_FAILED`, `TIMEOUT`,
`DEADLINE_EXCEEDED`, `CONNECT_ERROR`, `IO_ERROR`, `CIRCUIT_OPEN`, `BULKHEAD_FULL`, `AUTH_ERROR`,
`RESPONSE_TOO_LARGE`.

**Run event types** (owner story in brackets):
- **Run lifecycle:** `RUN_ACCEPTED`, `RUN_STARTED` [4.1]; `RUN_COMPLETED`,
  `RUN_COMPLETED_WITH_ERRORS`, `RUN_FAILED` [4.6]; `RUN_TIMED_OUT` [5.1]; `RUN_RECOVERED`,
  `STEP_OUTCOME_UNKNOWN` [5.5]; `RUN_CANCEL_REQUESTED`, `RUN_CANCELLED`, `STEP_RESOLVED` [5.7];
  `RUN_RESUMED` [5.8].
- **Step execution:** `STEP_SCHEDULED`, `REQUEST_SENT`, `RESPONSE_RECEIVED`, `STEP_SUCCEEDED`,
  `STEP_FAILED` [4.3]; `STEP_SKIPPED` [4.4/4.5]; `STEP_FALLBACK_APPLIED` [4.6].
- **Resilience and auth:** `ATTEMPT_FAILED`, `RETRY_SCHEDULED` [5.2]; `CIRCUIT_OPEN_REJECTED`
  [5.3]; `BULKHEAD_REJECTED` [5.4]; `TOKEN_REFRESHED` [2.2].
- **Compensation:** `COMPENSATION_STARTED`, `COMPENSATION_REQUEST_SENT`,
  `COMPENSATION_STEP_SUCCEEDED`, `COMPENSATION_STEP_FAILED`, `COMPENSATION_COMPLETED`,
  `COMPENSATION_FAILED` [5.6].
- **Async steps and callbacks:** `ASYNC_ACCEPTED`, `ASYNC_ACK_SUPERSEDED_BY_CALLBACK` [6.1];
  `CALLBACK_RECEIVED`,
  `CALLBACK_DUPLICATE`, `CALLBACK_LATE`, `CALLBACK_REJECTED` [6.2]; `RUN_WAITING_CALLBACK`,
  `RUN_COMPLETED_PENDING_ASYNC` [6.3]; `CALLBACK_TIMED_OUT` [6.4].
- **Caller exchange and webhooks:** `CALLER_WEBHOOK_SENT`, `CALLER_WEBHOOK_FAILED`,
  `CALLER_WEBHOOK_REDELIVERED` [6.5]; `INBOUND_REQUEST_RECEIVED`, `CALLER_RESPONSE_SENT` [7.7].
- **Data protection:** `PAYLOAD_ACCESSED` [7.4]; `RUN_HOLD_SET`, `RUN_HOLD_CLEARED` [7.8];
  `RUN_DATA_ERASED` [7.10].

## 11. Configuration catalog (`apiorch.*`)

| Key | Default | Story |
|---|---|---|
| `apiorch.instance-id` | `${HOSTNAME}` or a random UUID | US-5.5 |
| `spring.application.name` | `apiorch-service` | US-1.1 |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `true` (Boot's auto-configured `ObjectMapper` sets `false`, ADR-003 F7) | US-1.1 |
| `spring.flyway.locations` | `classpath:db/migration/common,classpath:db/migration/{vendor}` | US-1.1 |
| `spring.jpa.properties.hibernate.jdbc.time_zone` / `spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type` | `UTC` / `TIMESTAMP` (ADR-003 F6) | US-1.1 |
| `apiorch.engine.lock-timeout-ms` | `2000` (bounded row-lock wait, ADR-003 F5) | US-5.5 |
| `server.forward-headers-strategy` / `server.tomcat.remoteip.internal-proxies` | `native` / `10.x.x.x` (trusted load balancer addresses) | US-1.4 |
| `apiorch.callback.expected-public-host` | — (startup guard outside `local`) | US-1.4 |
| `apiorch.security.mode` | `jwt` (`dev` is allowed only when `local` is active) | US-1.2 |
| `apiorch.security.roles-claim` / `apiorch.security.principal-claim` | `roles` / `sub` | US-1.2 |
| `apiorch.security.metrics-allowed-cidrs` | `10.0.0.0/8,127.0.0.1/32` | US-1.2 |
| `apiorch.correlation.header` | `X-Correlation-Id` | US-1.3 |
| `apiorch.endpoints.allow-http` | `false` (`true` in `local`) | US-2.1 |
| `apiorch.endpoints.allowed-hosts` | `[]` (exact or `*.suffix`; required for any endpoint base URL or token URL) | US-2.1, US-2.2 |
| `apiorch.endpoints.refresh-interval-ms` | `10000` | US-2.1 |
| `apiorch.secrets.cache-ttl-ms` | `300000` | US-2.2 |
| `apiorch.secrets.env-prefix` / `apiorch.secrets.file-dir` | `APIORCH_SECRET_` / `/run/secrets` | US-2.2 |
| `apiorch.definition.max-steps` / `apiorch.definition.max-document-bytes` | `50` / `524288` | US-3.1 |
| `apiorch.definition.latest-cache-ttl-ms` | `5000` | US-3.3 |
| `apiorch.definition.require-four-eyes` | `false` | US-3.3 |
| `apiorch.definition.dsl-version` | `1` (a build constant, not configuration) | US-3.1 |
| `apiorch.run.default-sync-wait-ms` / `apiorch.run.max-sync-wait-ms` | `10000` / `30000` | US-4.1 |
| `apiorch.run.max-input-bytes` | `1048576` | US-4.1 |
| `apiorch.run.allow-version-override` | `false` (`true` in non-prod) | US-4.1 |
| `apiorch.run.max-response-bytes` | `5242880` | US-4.3 |
| `apiorch.expression.timeout-ms` / `apiorch.expression.max-depth` | `1000` (validated 500–5000; wall-clock, see ADR-002) / `200` | US-4.3 |
| `apiorch.expression.max-length` / `apiorch.expression.max-nesting` | `4096` / `64` | US-3.2 |
| `apiorch.executor.step-io.size` / `apiorch.executor.step-io.queue-capacity` | `64` / `1000` | US-4.3 |
| `apiorch.run.max-parallel-steps` | `10` | US-4.4 |
| `apiorch.run.default-run-timeout-ms` | `60000` | US-5.1 |
| `apiorch.resilience.defaults.retry.*` | `max-attempts=3, backoff=EXPONENTIAL_JITTER, initial-ms=200, max-ms=2000` | US-5.2 |
| `apiorch.resilience.defaults.circuit-breaker.*` | `sliding-window-size=20, minimum-calls=10, failure-rate-threshold=50, slow-call-duration-ms=3000, slow-call-rate-threshold=80, wait-in-open-ms=30000, half-open-calls=3` | US-5.3 |
| `apiorch.resilience.defaults.bulkhead.*` | `max-concurrent-calls=25, max-wait-ms=0` | US-5.4 |
| `apiorch.admission.max-in-flight-runs` | `500` | US-5.4 |
| `apiorch.lease.duration-ms` / `apiorch.lease.renew-interval-ms` | `30000` / `10000` | US-5.5 |
| `apiorch.recovery.sweep-interval-ms` | `15000` | US-5.5 |
| `apiorch.compensation.deadline-ms` | `300000` | US-5.6 |
| `apiorch.callback.public-base-url` | required outside `local` | US-6.1 |
| `apiorch.callback.keys.<kid>` / `apiorch.callback.active-kid` | secret refs / — | US-6.1 |
| `apiorch.callback.max-body-bytes` | `1048576` | US-6.2 |
| `apiorch.callback.timeout-sweep-interval-ms` | `10000` | US-6.4 |
| `apiorch.outbox.signing-key-ref` / `apiorch.outbox.allowed-hosts` | — / `[]` | US-6.5 |
| `apiorch.outbox.dispatch-interval-ms` / `apiorch.outbox.max-attempts` | `2000` / `10` | US-6.5 |
| `apiorch.outbox.max-concurrency` / `apiorch.outbox.per-host-concurrency` / `apiorch.outbox.claim-ms` | `16` / `4` / `60000` | US-6.5 |
| `apiorch.logging.default-capture` / `apiorch.logging.default-max-body-bytes` | `METADATA` / `262144` | US-7.3 |
| `apiorch.logging.always-mask-headers` | `Authorization, Proxy-Authorization, Cookie, Set-Cookie, X-Api-Key, X-Signature, X-Callback-Token` | US-7.3 |
| `apiorch.logging.always-mask-keys` | `password, secret, token, otp, pin` | US-7.3 |
| `apiorch.logging.include-bodies-in-logs` | `false` | US-7.3 |
| `apiorch.context.encryption-keys.<kid>` / `apiorch.context.active-kid` | secret refs (required outside `local`) / — | US-7.6 |
| `apiorch.retention.payload-days` / `apiorch.retention.run-days` | `30` / `180` | US-7.8 |
| `apiorch.retention.context-hours-after-terminal` / `apiorch.retention.resumable-context-days` | `24` / `7` | US-7.8 |
| `apiorch.retention.cron` / `apiorch.retention.max-runtime-minutes` | `0 30 2 * * *` / `60` | US-7.8 |
| `apiorch.retention.partition-months-ahead` / `apiorch.retention.partition-cron` | `3` / `0 0 1 * * *` | US-7.8 |
| `apiorch.seed.enabled` | `false` (`true` in `local`) | US-8.1 |

## 12. Roles

| Role (JWT claim value) | Who | Can |
|---|---|---|
| `apiorch.run` | Calling System | Start runs of pipelines it is allowed to call (US-4.8); read **its own** runs and timelines |
| `apiorch.admin` | Pipeline Administrator | Manage endpoints and pipelines (draft, validate, publish, rollback, dry run, export and import, disable) |
| `apiorch.ops` | Operations Engineer | Read all runs, attempt bodies and admin configuration (GET); cancel, resolve, resume, redeliver, erase; reset circuits; disable pipelines |

## 13. Story template

```markdown
# US-<e>.<n> <Title>
**Epic:** E<e> <name> · **Priority:** Must|Should|Could · **Size:** S|M|L · **Depends on:** US-x.y · **Release:** R1|R2|R3

## User story
As a <role>, I want <capability>, so that <benefit>.

## Description
Context, scope, explicit out-of-scope.

## Acceptance criteria
- [ ] AC1 — Given … When … Then …

## Technical details
Components, API contract, data, configuration, logging/metrics, security, test notes.
```

**Definition of Done (every story):**
- All ACs are automated. Unit tests cover logic. Integration tests use WireMock for downstream
  APIs and H2 in `MODE=MSSQLServer` for the database.
- `./mvnw verify` passes on H2 (no Docker) on the developer's machine, and, where the story has
  `slow` tests or touches migrations or `SqlDialect`, `./mvnw verify -Pslow` passes against
  Testcontainers SQL Server 2022 before merge (§13).
- Any AC about row locking, leases, concurrent writers, partitions, grants, `ISJSON` checks or
  filtered indexes has its test in the `slow` tier, on SQL Server (ADR-003 rule 6). Engine SQL runs
  through the shared `DatabaseContract` suite on both databases.
- OpenAPI is updated for any API change.
- No secret or unmasked sensitive value appears in logs or API responses.
- README sections 9–11 are updated if the story adds an endpoint, table or config key.
- **Timing assertions** use ordering, overlap or generous upper bounds. Latency targets live only
  in the tagged performance suite (US-8.2).
- **Time comes from an injected `Clock`,** never `Instant.now()` or `System.currentTimeMillis()`,
  except where the story explicitly needs database time (leases, ShedLock, retention cutoffs), which
  comes from `SYSUTCDATETIME()`. Deadlines, retry backoff, callback timeouts, token expiry and all
  four sweepers are time-driven, and a fixed `Clock` turns most timing assertions into
  deterministic ones. An ArchUnit rule enforces it.
- **Architecture rules hold** (`ArchUnitRulesTest`, from US-1.1):
  - no class in `engine`, `expression` or `definition` touches `RestClient`, `HttpClient`,
    `JdbcClient` or a repository — these must stay pure so the dry run (US-3.5) can reuse them;
  - no HTTP client call happens inside a `@Transactional` method, so no row lock is ever held
    across a network call;
  - `pipeline_run` is written only through `JdbcClient` (README §5.1 rule 2);
  - package dependencies follow README §5.2, and `observability` depends on nothing in `run`;
  - product code never imports `tools.jackson` (Jackson 3). Jackson 2 is the only line
    (ADR-003 F7);
  - SQL that differs between SQL Server and H2 lives only in `SqlDialect`. Nothing outside it
    branches on the database, and nothing in `engine` imports the H2 shim package `…support.h2`
    (ADR-003 rules 3 and 4).
- **Sensitive values never go into a log message placeholder.** A value rendered through `{}`
  lands in the free-text `message` field, which field-path masking does not reach (ADR-001 F5,
  still valid under ADR-003).
  Pass such values as `StructuredArguments` **without** a placeholder (they become fields and are
  masked), or not at all. The log value masks in US-7.3 are the safety net, and its canary test
  fails the build if a known secret appears in any log line.

**Test tiering.** There is no CI server: every build and test runs on a developer's machine with
`./mvnw`. Tests carry a JUnit tag and run in three tiers, so the everyday build stays fast and
trusted, and the Docker-based tier runs when it matters.

| Tier | Tag | Runs | Contents |
|---|---|---|---|
| Fast | none (`*Test`) | Every `./mvnw verify`, surefire | Unit tests, ArchUnit, table-driven rules |
| Component | `it` (`*IT`) | Every `./mvnw verify`, failsafe. Must pass before every commit to `main` | One Spring context, **H2 in MSSQL mode** (no Docker), WireMock |
| Slow | `slow` | `./mvnw verify -Pslow` (needs Docker): before merging a story that has `slow` tests or touches migrations or `SqlDialect`, and by one developer on the integrated `main` before every sprint review | **Testcontainers SQL Server 2022.** The `DatabaseContract` suite; every lock, lease, concurrency, partition, grant and `ISJSON` test (ADR-003 rule 6); two-context, multi-minute, race-loop and chaos tests — `LeaseRenewalIT`, `ConcurrentSweepIT`, `CallbackTimeoutBatchIT`, `AdvanceRaceIT`, `OutboxReclaimIT`, `RetentionJobIT` and the rest of US-8.2 |

A red slow run blocks the merge and the sprint review demo, so the slow tier is not optional — it
is simply not part of every build. The result of the pre-review slow run (commit and pass/fail)
is recorded in the sprint review notes.

**Coverage.** The flat 80% line gate (US-1.1 AC9) is the floor, not the target: it pushes toward
low-value tests on DTOs while leaving branches in the engine untested. JaCoCo therefore adds a
**branch** rule of 85% on `engine`, `expression`, `http`, `callback` and `outbox` — the packages
where a missed branch is a stuck or duplicated transaction — and the line rule covers the rest.

## 14. Non-functional requirements (targets proven by US-8.2)

| Area | Target |
|---|---|
| Throughput | 100 runs/s sustained per 3-node cluster, 5 steps per run, full capture |
| Overhead | apiorch adds ≤ 20 ms p95 per step and ≤ 50 ms p95 per run, excluding downstream time |
| Availability | 99.9% monthly for the inbound API |
| Durability | RPO 0 for run state, since every transition is committed before it is acted on |
| Recovery | A run on a crashed node resumes within `lease.duration-ms + recovery.sweep-interval-ms` (≤ 45 s); RTO for the service ≤ 5 min |
| Storage | Sized from US-8.2 measurements. Planning figure: about 4 KB per attempt at `FULL` capture, so 100 runs/s × 5 steps ≈ 170 GB/day before purge, and ≈ 5 TB at 30-day payload retention. That is the worst case: `FULL` is an explicit per-pipeline choice and `METADATA` is the default (US-7.3), so this figure applies only to the pipelines that opt in. Reclaimed by dropping monthly partitions (§10), not by row deletes |

**Capacity budget (per node).** These keys describe one system and must be sized together, not
chosen independently. US-8.2 measures the real ratios and this table is updated from its report.

| Resource | Key | Relationship |
|---|---|---|
| Concurrent downstream calls | `apiorch.executor.step-io.size` (64) | The hard ceiling on in-flight HTTP per node: `size / mean downstream seconds` ≈ calls/s. At the §14 target (100 runs/s × 5 steps over 3 nodes ≈ 170 calls/s per node) and a 80 ms median / 600 ms p99, 64 threads is the planning figure to confirm |
| Database connections | `spring.datasource.hikari.maximum-pool-size` (**set explicitly; the Boot default of 10 is far too small**) | Every attempt boundary opens a short transaction, so the pool must cover `step-io.size` + the sweepers (recovery, callback timeout, outbox, retention) + inbound request threads. Starting point: `step-io.size + 16`. SQL Server's `user connections` limit (0 = up to 32,767) and the server's worker-thread count must cover pool × nodes + headroom |
| Runs driven | `apiorch.admission.max-in-flight-runs` (500) | A run holds no thread while waiting, so this may exceed `step-io.size`; but `max-in-flight-runs × max-parallel-steps` is the worst-case demand on `step-io`, and the excess is shed as `BULKHEAD_FULL`, not queued indefinitely |
| Parallel steps per run | `apiorch.run.max-parallel-steps` (10) | See above |

**Runtime note.** The `step-io` ceiling exists because downstream calls block a platform thread
on JDK 17. If the runtime moves to JDK 21, `step-io` becomes a virtual-thread executor, the
ceiling becomes the per-endpoint bulkhead alone, and only the connection budget still binds. The
JDK choice is therefore a capacity decision, not just a dependency one. Boot 3.5 supports
JDK 21, but ADR-003's probe ran on JDK 17 only, so a JDK 21 move must first re-run
`tools/stack-probe-b35-mssql` on JDK 21. Boot 3.5 is out of open-source support (ADR-003 F1), so
any runtime change is weighed against the separate Boot 4 upgrade decision.

## 15. Platform integration map

What apiorch integrates with rather than builds, and the one place the boundary is contested.

| Capability | Owner | How apiorch relates to it |
|---|---|---|
| **Edge** (public address) | Each environment's own load balancer or ingress | **Not a platform integration** (US-1.4). apiorch is independent: no shared API gateway and no service registry |
| **Identity** (users, roles, approval limits) | Platform HRMS adapter | **Integrates by not storing any of it.** Roles come from the SSO JWT claim (US-1.2); there is no user, role or approval-limit table anywhere in §10 |
| **Workflow and approvals** | Platform workflow-service | **Integrates when needed, nothing today.** US-3.3's `require-four-eyes` is a same-actor check, not an approval chain; anything beyond it (checker queues, DOA, SLA, send-back) goes to the workflow platform |
| **Audit** | No platform owner yet | **Local, per the interim rule.** `run_event`, `step_attempt` and `endpoint_audit` are product tables. No shared audit service is built |
| **Dashboards and charts** | Dashboard Composer | **Not used, and nothing built.** US-7.4 serves JSON; no chart library appears in US-1.1's dependencies |
| **Documents and reports** | Reporting service | **Not used, and nothing built.** No PDF, Word or Excel generation anywhere in this backlog |
| **Notifications and scheduling** | No platform owner yet | **Local, per the interim rule.** Caller webhooks (US-6.5) are a machine-to-machine protocol, not user notifications. The four scheduled jobs — recovery sweeper, callback-timeout sweeper, outbox dispatcher, retention and partition maintenance — are product-internal and listed here as the rule requires |
| **Data connectors** | No platform owner yet | **No registry built.** `endpoint` (US-2.1) is this product's own downstream-API table, not a general data-source registry |
| **Tenancy** | No platform owner yet | Every externally visible name carries the product code: the service name `apiorch-service` |

**The one judgement worth stating plainly:** the run state machine in E4 and E6 is **legitimately
local** and must not be pushed onto the workflow platform. It is a data-plane execution engine for
machine-to-machine API orchestration at transaction rates — leases, fencing, callbacks and
compensation — not a human approval process. A BPMN engine is the wrong tool for it by orders of
magnitude. The platform rule bites on *approvals*, and the only approval in this backlog is
US-3.3's.

## 16. Open questions (need a decision outside engineering)

| # | Question | Owner | Affects |
|---|---|---|---|
| Q1 | Retention periods (24 h context, 7 days resumable context, 30 days payloads, 180 days runs) and erasure scope under the DPDP Act 2023 | Compliance / DPO | US-7.8, US-7.10 |
| Q2 | Rules on storing Aadhaar numbers, even masked and encrypted. Should those fields be dropped entirely? | Compliance | US-7.3, US-7.6 |
| Q3 | Non-JSON bodies (XML/SOAP) are logged as **size only** (placeholder). Is that acceptable, or must XML be stored with masking? | Business Owner + Security | US-7.3 |
| Q4 | Callers receive `200` with `status: FAILED` in sync mode, plus `X-Apiorch-Run-Status`. Do the calling teams accept this contract? | Calling-system owners | US-4.1 |
| Q5 | Inventory of the real async APIs and their callback styles (the R2 entry criterion) | Business Owner | US-6.1, US-6.6 |
| Q6 | Is a per-run hash chain over `run_event` required for tamper **evidence**, on top of the grant model that already prevents the application from rewriting history? | Compliance / Security | US-7.1 |
| Q7 | Investigations sometimes need **unmasked** values. Today that means ad-hoc SQL against production, which nothing audits. Should there be an operator endpoint with its own `PAYLOAD_ACCESSED` trail and dual approval instead? | Security Officer + Compliance | US-7.3, US-7.4 |
