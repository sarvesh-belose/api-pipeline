# ADR-001: Runtime stack for apiorch

| | |
|---|---|
| **Status** | **Superseded by [ADR-003](ADR-003-boot35-sqlserver.md)** (2026-11-04): the product owner fixed Spring Boot 3.5.14 and SQL Server. Kept as evidence that the Boot 4.1 line works, for the later upgrade decision |
| **Date** | 2026-11-04 |
| **Gate** | G1 in [R1-implementation-plan.md](../R1-implementation-plan.md) §3; US-1.1 "scaffold check"; US-1.4 dependency risk |
| **Decision** | **Spring Boot 4.1.x + Spring Cloud 2025.1.x on JDK 17.** JDK 21 is verified as a drop-in |

## Context

US-1.1 makes the stack a gate. The design uses Jackson heavily (canonical checksums, `JsonNode`
masking, strict DSL binding), and US-1.4 needs a Spring Cloud release train for `eureka-client`.
The backlog assumed Boot 4.0.x on JDK 17, with Boot 3.5.x on JDK 21 as the fallback. The gate had
two questions:
1. Does every library pair with the chosen Boot line?
2. Is that line supported for as long as we need it?

## Decision

Use **Spring Boot 4.1.2** (latest 4.1 patch at scaffold time) with **Spring Cloud 2025.1.4**, on
**JDK 17**. Library versions:

| Component | Version | Source |
|---|---|---|
| Spring Boot | **4.1.2** | parent |
| Spring Framework / Spring Security | 7.0.10 / 7.1.2 | Boot BOM |
| Hibernate ORM / Jakarta Persistence | 7.4.13.Final / 3.2.0 | Boot BOM |
| Flyway | **12.4.0** (major version above Boot 4.0's 11.14) | Boot BOM |
| Jackson | **3.1.7** (`tools.jackson`); 2.21.7 also present transitively, see F7 | Boot BOM |
| Micrometer / Tomcat / Logback | 1.17.2 / 11.0.27 / 1.5.38 | Boot BOM |
| JUnit Jupiter / Testcontainers / Awaitility | 6.0.3 / 2.0.5 / 4.3.0 | Boot BOM |
| PostgreSQL JDBC / Caffeine | 42.7.14 / 3.2.4 | Boot BOM |
| Spring Cloud | **2025.1.4** (commons 5.0.4, `spring-cloud-netflix-eureka-client` 5.0.3, Netflix `eureka-client` 2.0.6) | imported BOM |
| springdoc-openapi | **3.2.0** (built on Boot 4.1.2) | pinned |
| logstash-logback-encoder | **9.0** (Jackson 3) | pinned |
| Resilience4j (circuitbreaker, retry, bulkhead, micrometer) | 2.4.0 | pinned |
| ShedLock (spring, jdbc-template) | 7.10.1 (tested by its authors on Boot 4.1.1) | pinned |
| JSONata | `com.dashjoin:jsonata` 0.9.10, no transitive dependencies. Its use is decided by **G2** | pinned |
| ArchUnit / WireMock (standalone) / JaCoCo | 1.5.1 / 3.13.2 / 0.8.16 | pinned |

**Starter and module names confirmed for Boot 4:**
- **Starters:** `spring-boot-starter-webmvc`, `-validation`, `-actuator`, `-data-jpa`, `-flyway`,
  `-restclient`, `-security` and `-security-oauth2-resource-server`.
- **Test support:** `spring-boot-testcontainers`, plus the Testcontainers modules
  `testcontainers-postgresql` and `testcontainers-junit-jupiter`. The container class is
  `org.testcontainers.postgresql.PostgreSQLContainer`.
- **`EndpointRequest`** is in `org.springframework.boot.security.autoconfigure.actuate.web.servlet`.

## Options considered

| Option | Pairing works? | OSS support ends | Verdict |
|---|---|---|---|
| **Boot 4.1.2 + Cloud 2025.1.4** | **Yes: probe B passes 12/12 on JDK 17 and on JDK 21.** Spring Cloud 2025.1.x declares `4.0.x` and `4.1.x` compatible, and its verifier (on by default) passed at startup | Boot 4.1: **2027-07-31**. Cloud 2025.1: **2027-07-31** | **Chosen.** It is the only line supported through R1 and R2 |
| Boot 4.0.9 + Cloud 2025.1.4 | Yes: probe A passes 12/12 on JDK 17 | Boot 4.0: **2026-12-31** | Rejected. Open-source support ends before R1 finishes (about Feb 2027), which would force a mid-release upgrade for no gain. 4.0 → 4.1 is otherwise a small move, as the identical probe sources show |
| Boot 3.5.16 + Cloud 2025.0.3 on JDK 21 (the backlog's fallback) | Not built | Boot 3.5: **2026-06-30, already ended**; commercial support only (to 2032) | Rejected. Using it would mean adopting a line already out of open-source support, on Jackson 2, Spring 6 and JUnit 5. Revisit only if a commercial Spring subscription is in place |
| Boot 4.2 + Cloud 2026.0 | Not available | — | Not yet GA: Spring Cloud 2026.0.0 is at RC1, built on Boot 4.2.0-RC1. This is the next upgrade target (see Consequences) |

Support dates come from `https://api.spring.io/projects/{spring-boot,spring-cloud,spring-framework}/generations`,
retrieved on 2026-11-04. Spring Framework 7.0.x OSS support ends 2027-07-31.

## Evidence

Two throwaway probe projects with identical sources were built and run on this machine. They
differ only in the Boot parent, the springdoc version and the JDK. Each probe has one Spring
context, PostgreSQL 16 (`postgres:16-alpine`) through Testcontainers, and WireMock.

| Test | What it proves |
|---|---|
| Context start (all ITs) | Spring Cloud compatibility verifier, `eureka-client` on the classpath (disabled), logback config with `LogstashEncoder` 9 + masking decorator, Flyway migrations, JPA and the security chains all start together |
| `securedAsyncRunEndpoint` | Async MVC (`CompletableFuture`) behind the Spring Security 7 JWT resource server: `200` with role, `401` without token, `403` wrong role (US-1.2, US-4.1 shapes) |
| `callbackChainIgnoresUnrelatedBearer` | A `securityMatcher` chain without a resource server accepts a request with a junk `Authorization: Bearer` (US-1.2 AC4) |
| `actuatorChainOnManagementPort` | `EndpointRequest` chain on a separate management port: health and readiness `200`, Prometheus `403` (US-1.2 AC5) |
| `openApiDocs` | springdoc serves `/v3/api-docs` |
| `leaseSqlWithDatabaseTimeAndJpaMapping` | `JdbcClient` lease SQL using `CURRENT_TIMESTAMP + make_interval(secs => :secs)`; fenced `SELECT … FOR UPDATE`; the single claim predicate; Hibernate 7 `Instant` ↔ `timestamptz` and `jsonb` on an `@Immutable` entity (README §5.1) |
| `appendOnlyGrantAndMonthPartition` | Insert as the application role lands in the current-month partition (not default). `UPDATE` as that role is refused: `permission denied for table run_event` (US-7.1, README §10) |
| `shedLockedSchedulerRuns` | `@Scheduled` + `@SchedulerLock` with `JdbcTemplateLockProvider.usingDbTime()` on Spring 7 |
| `resilienceDecoratorsOverClonedRestClient` | `Retry → CircuitBreaker → Bulkhead(maxWait 0)` via `decorateCompletionStage` over a `RestClient` cloned from Boot's builder with `JdkClientHttpRequestFactory`. 503, 503, 200 gives exactly 3 requests; `TaggedCircuitBreakerMetrics` meters bound (US-5.2–5.4) |
| `jackson3StrictBindingCanonicalJsonAndDeepCopy` | Jackson 3: strict record binding rejects `dependOn`; canonical JSON sorts keys and keeps array order; `deepCopy` isolation (US-3.1, US-3.3, US-7.3) |
| `jsonataPartOfFieldMapping` | `$substring(steps.api1.body.account.number, 4, 3)` gives `"001"` (smoke only; G2 owns the real gate) |
| `logstashEncoder9MasksFieldsOnJackson3` | Path and value masking with the 9.0 API (US-7.3) |
| `ArchRulesTest` | ArchUnit 1.5.1 reads the class files and enforces a README §13 rule |

| Run | Result | Time |
|---|---|---|
| Probe A: Boot 4.0.9, Cloud 2025.1.4, JDK 17 | **12/12 pass** (4 unit, 8 IT) | 2 min 7 s |
| Probe B: Boot 4.1.2, Cloud 2025.1.4, JDK 17 | **12/12 pass** | 2 min 16 s |
| Probe B on JDK 21.0.11 | **12/12 pass** | 1 min 48 s |

To reproduce: start the Docker engine, then run `mvn -B -ntp verify` in the probe directory.
The probes are in the session scratchpad, so copy them into the repository if they are needed
for a re-run (see Follow-ups).

## Findings that change the backlog

| # | Finding | Affects | Correction |
|---|---|---|---|
| F1 | Boot 4.0 OSS support ends 2026-12-31, and the Boot 3.5 fallback has been out of OSS support since 2026-06-30 | README §5.2, US-1.1, US-1.4, README §14 runtime note | Target **Boot 4.1.x + Spring Cloud 2025.1.x**. Remove the Boot 3.5/JDK 21 fallback recommendation |
| F2 | Spring Cloud 2025.1.x officially supports Boot 4.0.x **and** 4.1.x | US-1.4 "Risk" paragraph | The risk is resolved. Record the pairing |
| F3 | `logstash-logback-encoder` **9.0 is Jackson 3**, not Jackson 2 | US-1.1 scaffold check | Drop the "Jackson 2" caveat |
| F4 | logstash 9.0 replaced the decorator API: XML uses `<decorator class="…MaskingJsonGeneratorDecorator">`, code uses `encoder.addDecorator(…)`, and a programmatic decorator must be `start()`ed | US-7.3 | Update the config snippet and the component notes |
| F5 | **A path mask does not cover the `message` field.** A structured argument used in a `{}` placeholder is rendered into the message text in clear (`"message":"login password=s3cret"` while the `password` field was masked). Observed in the probe | US-7.3, US-1.3 | Add value masks for the always-mask keys (e.g. `(?<=password=)\S+`), **and** a coding rule (with an ArchUnit or Checkstyle check) that sensitive values are never passed to message placeholders |
| F6 | **Jackson 3's default is `FAIL_ON_UNKNOWN_PROPERTIES = false`** (Jackson 2's was `true`) | US-3.1, US-2.1/US-2.2 (unknown and literal-secret field rejection), any strict request body | Set `spring.jackson.deserialization.fail-on-unknown-properties=true` for the application mapper, keep the explicit `DslJsonMapper` setting, and add a test that an unknown field in an admin body returns `400` |
| F7 | Jackson **2** (2.21.7) is still on the classpath, through Netflix `eureka-client` 2.0.6 (and through Flyway 11 on Boot 4.0) | US-1.1 ArchUnit rules | Add a rule: product code may import `com.fasterxml.jackson.annotation` only, never `com.fasterxml.jackson.databind` or `.core`, so the two lines never mix |
| F8 | PostgreSQL interval arithmetic needs an explicit form. `CURRENT_TIMESTAMP + make_interval(secs => :secs)` works with `JdbcClient` named parameters (probe). `CURRENT_TIMESTAMP + :lease` with a numeric parameter fails: `operator does not exist: timestamp with time zone + bigint` (verified on `postgres:16-alpine` with `PREPARE`) | US-5.5, README §5.1 rule 5, US-6.5 claim SQL | Use `make_interval(secs => :leaseSeconds)` (or `:ms * INTERVAL '1 millisecond'`) in every lease and claim statement |
| F9 | Spring translates SQLState **42501** (insufficient privilege) to `BadSqlGrammarException`. The message is "bad SQL grammar", and the real text is only in the root cause | US-7.1 grant tests, ops diagnostics | Detect privilege denials by SQLState `42501`, not by exception type or message |
| F10 | Flyway **12** (Boot 4.1) ran the partitioned DDL, including `DO $$ … $$` blocks, unchanged | US-1.1, US-7.1 | None. Recorded so the major-version bump is not a surprise |
| F11 | Spring Data JPA does not pick up a repository interface **nested** in another class unless `considerNestedRepositories = true` | Coding conventions | Keep repositories top-level |
| F12 | The **Docker engine was not running** on this machine (Docker Desktop installed, WSL2 distribution present, CLI 29.9.2), so Testcontainers failed until it was started. `~/.testcontainers.properties` points at the Docker Desktop pipe. Engine 29.9.2 reports API 1.56 (min 1.40), and Testcontainers 2.0.5 works with it | R1 plan S0 item S2 and risk R7 | Developer setup: start Docker Desktop before `mvn verify`. CI: confirm Docker-capable runners in S0 |

## Consequences

- **Upgrade window.** Boot 4.1 and Spring Cloud 2025.1 OSS support both end **2027-07-31**.
  Schedule the move to Boot 4.2.x + Spring Cloud 2026.0.x once 2026.0 is GA. That is expected well
  before mid-2027, so plan it as an R2/R3 hardening item with a re-run of this probe as its entry
  check.
- **JDK.** JDK 17 stays the baseline, as decided. JDK 21 is proven to run the same stack (probe B),
  so the README §14 runtime note, "virtual threads as a capacity decision", remains open without a
  framework change.
- **Two Jackson lines** coexist, by transitive necessity (F7). The ArchUnit rule keeps them apart.
- **Pinned versions** (springdoc, logstash encoder, Resilience4j, ShedLock, JSONata, ArchUnit,
  WireMock, JaCoCo) are managed in one `<properties>` block, and changing them requires re-running
  this probe.

## Follow-ups

1. ~~Apply F1–F9 to the affected stories and the README.~~ **Done 2026-11-04:**
   - README §5.1 rule 5, §5.2, §11, §13 and the §14 runtime note;
   - US-1.1 (versions, AC11 Jackson rule, new AC12 strict JSON), US-1.3, US-1.4;
   - US-2.1, US-2.2, US-3.1 (strict binding);
   - US-5.5, US-6.5 (`make_interval`);
   - US-7.1 (SQLState 42501);
   - US-7.3 (9.0 decorator API, new AC9 message masking, AC10 canary);
   - the R1 plan (G1 row, risk R1).
2. ~~G2 (expression engine gate).~~ **Done:** see [ADR-002](ADR-002-expression-engine.md).
3. ~~Copy the recommended probe into the repository.~~ **Done:** [`tools/stack-probe/`](../../tools/stack-probe/) (see [tools/README.md](../../tools/README.md)).
