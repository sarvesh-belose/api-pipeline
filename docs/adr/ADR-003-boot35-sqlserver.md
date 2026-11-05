# ADR-003: Spring Boot 3.5.14, SQL Server in production, H2 (MSSQL mode) for dev and test

| | |
|---|---|
| **Status** | **Accepted 2026-11-05** at the Sprint 0 review (R1 plan §3, gate G1), with review decisions 1 and 4 as recommended ([acceptance record](../sprint0/G-adr-acceptance.md)). **Supersedes [ADR-001](ADR-001-stack.md)** |
| **Date** | 2026-11-04 |
| **Gate** | G1 (re-run) in [R1-implementation-plan.md](../R1-implementation-plan.md) §3; US-1.1 "scaffold check" |
| **Decision** | **Spring Boot 3.5.14 + Spring Cloud 2025.0.3 on JDK 17. SQL Server in production; H2 2.3 in `MODE=MSSQLServer` for local runs and the fast test tier; real SQL Server (Testcontainers) for the nightly tier** |

## Context

ADR-001 chose Boot 4.1.2 on PostgreSQL. The product owner then set two constraints:
- **Spring Boot 3.5.14**, exactly. The owner confirmed this knowing Boot 3.5's open-source support
  ended on 2026-06-30.
- **SQL Server** as the production database, with **H2 in SQL Server compatibility mode** for
  development and tests. That gives an H2 fast tier on every build plus a nightly tier on real
  SQL Server.

The gate questions were:
1. Does every library pair with Boot 3.5.14?
2. Can one set of migrations and engine SQL run on both H2 and SQL Server?
3. Where they cannot, which differences are safe and which hide bugs?

## Decision

| Component | Version | Source |
|---|---|---|
| Spring Boot | **3.5.14** | parent |
| Spring Framework / Spring Security | 6.2.18 / 6.5.10 | Boot BOM |
| Hibernate ORM | 6.6.49.Final | Boot BOM |
| Flyway | 11.7.2, **plus `flyway-sqlserver`** (SQL Server support is a separate module from Flyway 10 on) | Boot BOM |
| H2 / mssql-jdbc | 2.3.232 / 12.10.2.jre11 | Boot BOM |
| Jackson | **2.21.2 only** (`com.fasterxml.jackson`); there is no Jackson 3 on this line | Boot BOM |
| Micrometer / Tomcat / Logback | 1.15.11 / 10.1.54 / 1.5.32 | Boot BOM |
| JUnit Jupiter / Testcontainers | 5.12.2 / **1.21.4** (`org.testcontainers:mssqlserver`, `junit-jupiter`) | Boot BOM |
| Spring Cloud | **2025.0.3** (commons and `spring-cloud-netflix-eureka-client` 4.3.3, Netflix `eureka-client` 2.0.6) | imported BOM |
| springdoc-openapi | **2.8.17** | pinned |
| logstash-logback-encoder | **8.1** (Jackson 2) | pinned |
| Resilience4j (circuitbreaker, retry, bulkhead, micrometer) | 2.4.0 | pinned |
| ShedLock (spring, jdbc-template) | **6.10.0** | pinned |
| JSONata / ArchUnit / WireMock (standalone) / JaCoCo | 0.9.10 / 1.5.1 / 3.13.2 / 0.8.16 | pinned (unchanged from ADR-001 and ADR-002) |

**Starter and module names on Boot 3.5:**
- **Starters:** `spring-boot-starter-web` (not `-webmvc`), `-validation`, `-actuator`,
  `-data-jpa`, `-security`, `-oauth2-resource-server`.
- **Flyway:** there is no Flyway starter; depend on `flyway-core` plus `flyway-sqlserver`.
- **`RestClient`** is in `spring-web`; there is no `-restclient` starter.
- **`EndpointRequest`** is in `org.springframework.boot.actuate.autoconfigure.security.servlet`.
- **Test container:** `org.testcontainers.containers.MSSQLServerContainer`
  (`mcr.microsoft.com/mssql/server:2022-latest`, `.acceptLicense()`), wired with `@ServiceConnection`.

**Database rules.** These replace the PostgreSQL forms in README §5.1 and §10.
1. **One SQL text, SQL Server syntax.** Migrations and engine SQL are written for SQL Server:
   - types: `UNIQUEIDENTIFIER`, `DATETIME2(6)` holding UTC, `BIT`, and `NVARCHAR(MAX)` for JSON
     documents;
   - functions: `SYSUTCDATETIME()` and `DATEADD(MILLISECOND, :ms, SYSUTCDATETIME())`.

   H2 runs the same text in `MODE=MSSQLServer`. The JDBC URL also sets `DATABASE_TO_UPPER=FALSE`,
   `CASE_INSENSITIVE_IDENTIFIERS=TRUE` and `DB_CLOSE_DELAY=-1`.
2. **Migration layout.** `spring.flyway.locations: classpath:db/migration/common,classpath:db/migration/{vendor}`.
   - `common/` holds everything both databases accept.
   - `sqlserver/` holds partitioning, `ISJSON` checks, roles and grants.
   - `h2/` holds plain equivalents of those objects, plus the shim migration (rule 3).
   - Version numbers are global across the folders. A vendor file and its counterpart share a
     version, for example `V2__run_event.sql` in both `h2/` and `sqlserver/`.
3. **H2 shims, only where H2 lacks a function.** H2 2.3 has no `SYSUTCDATETIME()` (F2).
   `h2/V0_1__h2_mssql_shims.sql` defines it as a Java alias, `CREATE ALIAS SYSUTCDATETIME FOR
   '…H2Functions.sysUtcDateTime'`, which returns `LocalDateTime.now(ZoneOffset.UTC)`. This keeps
   rule 1 intact. The shim class sits in a `…support.h2` package that ArchUnit forbids engine code
   from importing.
4. **The SQL that must differ is limited to one class, `SqlDialect`**, and both test tiers cover it:
   - the row lock (F3, F4): SQL Server `SELECT … WITH (UPDLOCK, ROWLOCK) WHERE …`, H2
     `SELECT … WHERE … FOR UPDATE`;
   - the bounded lock wait (F5): SQL Server prefixes `SET LOCK_TIMEOUT n;` in the same statement,
     H2 sets it per session;
   - the retention purge of the four partitioned tables (F9). SQL Server uses `TRUNCATE TABLE … WITH
     (PARTITIONS (n))` + `MERGE RANGE`. H2 has no partitions, so it uses a batched
     `DELETE TOP (n) … WHERE <ts> < :cutoff` loop. This is a `SqlDialect` method too, and only the
     SQL Server path counts as evidence (the `slow` tier).

   No other vendor branch is allowed. A new difference needs an entry in this ADR and a test in
   the database contract suite.
5. **`Instant` mapping.** Set both `hibernate.jdbc.time_zone=UTC` and
   `hibernate.type.preferred_instant_jdbc_type=TIMESTAMP` (F6). Production JVMs also run with
   `-Duser.timezone=UTC` as a second guard, but correctness must not depend on it. The probe passes
   with the JVM on IST.
6. **Lock-sensitive behaviour is proven on SQL Server, not on H2.** The nightly tier runs the
   database contract suite and every concurrency, lease, claim, partition, grant or JSON-check
   test against real SQL Server. A test that passes only on H2 is not evidence for these behaviours
   (F3).

## Options considered

| Option | Verdict |
|---|---|
| **Boot 3.5.14 + Cloud 2025.0.3, SQL Server + H2 MSSQL mode** | **Chosen** (owner constraint). Probe passes 24/24; findings F1–F16 below |
| Boot 4.1.2 + Cloud 2025.1.4 on PostgreSQL (ADR-001) | Superseded by the owner's constraints. It is still the technically preferred line for support length (see Consequences) |
| Boot 3.5.14 with SQL Server everywhere (no H2) | Rejected for the fast tier. Every build would need Docker and about 60 s to start SQL Server. Kept for the nightly tier |
| H2 in its native mode with a translation layer | Rejected. Two SQL dialects in the code is exactly what rule 4 keeps to one class |

**Support caveat.** Boot 3.5's open-source support ended **2026-06-30**. Commercial support
(Tanzu Spring) runs to 2032. 3.5.14 is not the newest 3.5 patch: Spring Cloud 2025.0.3 itself is
built on 3.5.15. Spring Cloud's compatibility verifier accepts 3.5.14 and the probe passes on it.
Patch CVEs published after 3.5.14 are not fixed unless a commercial subscription supplies later
patches (see Consequences).

## Evidence

Probe: [`tools/stack-probe-b35-mssql/`](../../tools/stack-probe-b35-mssql/), with one Spring context
per tier and WireMock. One abstract suite, `DatabaseContract`, runs unchanged on both databases.
Each finding is printed as a `G3|<db>|…` line.

| Test | H2 2.3.232 (MSSQL mode) | SQL Server 2022 (Testcontainers) |
|---|---|---|
| Context start: Flyway common + `{vendor}`, JPA, security chains, logback 8.1 with masking, Spring Cloud verifier, `eureka-client` (disabled) | Pass | Pass |
| `uuidUtcTimestampAndJsonTextRoundTrip`: `UUID` id, DB-default `created_at`, `Instant` within 10 s of the JVM's UTC clock while the JVM runs on IST, `NVARCHAR(MAX)` text | Pass (after F2, F6) | Pass |
| `leaseClaimPredicateOnDatabaseTime`: the single claim predicate on `SYSUTCDATETIME()` claims an expired lease once and never a live one, and `lease_epoch` increments | Pass | Pass |
| `rowLockSyntaxMatrix`: which lock forms parse | `FOR UPDATE` and the table hint both parse | Table hint parses; `FOR UPDATE` → **error 1003** |
| `rowLockBlocksSecondLocker`: a second transaction must wait for, then time out on, the held lock (lock-timeout codes only: H2 50200, SQL Server 1222) | `FOR UPDATE` blocks; **the table hint does not block** | Table hint blocks, times out at about 450 ms |
| `eventSeqAllocationIsGapFreeUnderConcurrency`: 16 writers × 25 events under the run lock | 400 events, max `seq` 400 | 400 events, max `seq` 400 |
| `shedLockedSchedulerRuns`: `@SchedulerLock` with `usingDbTime()` | Pass | Pass |
| `jsonValidityIsEnforced`: `ISJSON` check | n/a (no `ISJSON` in H2; F8) | Rejected, error 547 |
| `currentMonthLandsInItsPartition`: `RANGE RIGHT` monthly function | n/a | Current month → partition 2 of 4 |
| `appendOnlyGrantRefusesUpdate`: `EXECUTE AS USER` the application role | n/a | `INSERT` allowed; `UPDATE` → **error 229**, SQLState S0005 |
| `WebStackIT`: async MVC + JWT (`200`/`401`/`403`), callback chain ignores a junk bearer, actuator on the management port, `/v3/api-docs`, `Retry → CircuitBreaker → Bulkhead` over a cloned `RestClient` (503, 503, 200 = 3 requests, meters bound), Boot `ObjectMapper` default | Pass | (web is database-independent; H2 tier only) |
| `LibraryCompatTest`: Jackson 2 strict binding, canonical JSON, `deepCopy`; JSONata smoke; logstash 8.1 path + value masking | Pass | — |
| `ArchRulesTest` | Pass | — |

| Run | Result | Time |
|---|---|---|
| `mvn -B -ntp verify` (fast tier: unit + H2 + web) | **15/15 pass** (4 unit; 6 H2 contract + 5 web IT) | 1 min 27 s |
| `mvn -B -ntp verify -Pslow` (adds `SqlServerIT`, tag `slow`) | **24/24 pass** (adds 6 SQL Server contract + 3 SQL Server-only) | 2 min 43 s (image already pulled) |

To reproduce, start Docker Desktop and run `mvn -B -ntp verify -Pslow` in the probe directory.
The fast tier needs no Docker.

## Findings that change the backlog

| # | Finding | Affects | Correction |
|---|---|---|---|
| F1 | Boot 3.5 has been out of OSS support since 2026-06-30. Spring Cloud 2025.0.3 targets 3.5.15, but the verifier accepts 3.5.14 | README §5.2, §14 runtime note, US-1.1, US-1.4 | Record the owner's acceptance. Add an R1 risk: "unsupported line: CVE patches after 3.5.14 need a commercial subscription or a patch bump" |
| F2 | **H2 2.3 MSSQL mode has no `SYSUTCDATETIME()`** (`Function "SYSUTCDATETIME" not found`). `DATEADD(MILLISECOND, …)`, `UNIQUEIDENTIFIER`, `DATETIME2(6)`, `BIT` defaults and `NVARCHAR(MAX)` all work | US-1.1, README §10 | Rule 3: an H2-only shim migration with a Java alias. Do not switch the common SQL to `CURRENT_TIMESTAMP`, which is local time on SQL Server |
| F3 | **H2 parses `WITH (UPDLOCK, ROWLOCK)` and ignores it.** A second transaction got the "locked" row immediately. A suite that only ever ran on H2 would pass with **no locking at all** | README §5.1 rule 1, US-5.5, US-4.4, US-6.2, US-6.3, every concurrency test | Rule 4 (dialect lock) and rule 6 (prove locking on SQL Server). The contract suite asserts that the dialect form blocks, on both databases |
| F4 | SQL Server rejects `SELECT … FOR UPDATE` outside a cursor (error 1003) | README §5.1, US-5.5 | Rule 4. The README's `FOR UPDATE` wording becomes "the dialect row lock" |
| F5 | **`SET LOCK_TIMEOUT` sent as its own JDBC statement has no effect on SQL Server**, because mssql-jdbc runs prepared statements through `sp_executesql` and the `SET` reverts on return. The second locker waited the full 10 s | US-5.5 (bounded lock wait), US-3.3 (per-code publish lock) | `SqlDialect.withLockTimeout(sql, ms)` prefixes it in the same batch; config `apiorch.engine.lock-timeout-ms` (default 2000) |
| F6 | **Hibernate 6.6 maps `Instant` to `TIMESTAMP_UTC` by default and then ignores `hibernate.jdbc.time_zone`** on `DATETIME2`. Values read back shifted by the JVM offset (−5 h 30 min on this IST machine) | US-1.1, README §11, every `Instant` column | Rule 5: `hibernate.type.preferred_instant_jdbc_type=TIMESTAMP` plus `jdbc.time_zone=UTC`, and a round-trip test with a non-UTC JVM zone |
| F7 | **Only Jackson 2 is present.** A plain `JsonMapper` defaults to `FAIL_ON_UNKNOWN_PROPERTIES=true`, but **Boot's auto-configured `ObjectMapper` sets it to `false`** (measured) | US-1.1 AC11 and AC12, US-2.1, US-2.2, US-3.1 | Keep `spring.jackson.deserialization.fail-on-unknown-properties=true` and the AC12 test. Replace ADR-001 F7's ArchUnit rule (no `com.fasterxml.jackson.databind`) with "no `tools.jackson` imports" |
| F8 | H2 has no `ISJSON` and no partitioning, so those checks exist only in `sqlserver/` migrations | US-7.1, US-7.4, US-7.8 | JSON-validity, partition and grant tests are `slow`-tagged and run nightly. On H2, JSON validity is guaranteed only by the application writer |
| F9 | SQL Server partitioning: a `RANGE RIGHT` function `pf_month` on `DATETIME2(6)`, scheme `ps_month`, and a clustered PK `(run_id, seq, occurred_at)` on `ps_month(occurred_at)`. The partitioning column must be in the clustered key. Boundaries are created by dynamic SQL from `SYSUTCDATETIME()` | README §10, US-7.1, US-7.6 retention, US-7.4 | **One shared function `pf_month` and scheme `ps_month`** serve all four partitioned tables (`run_event`, `step_attempt`, `run_exchange`, `callback_inbox`), which all drop rows at the run retention. One `SPLIT` therefore rolls every table forward. A `MERGE` requires that month to be truncated on **all four** tables first, so the retention job truncates all four, then merges. Monthly roll-forward is `ALTER PARTITION SCHEME … NEXT USED` + `ALTER PARTITION FUNCTION … SPLIT RANGE`, run by the retention job. Retention drops via `TRUNCATE TABLE … WITH (PARTITIONS (n))` + `MERGE RANGE` |
| F10 | Append-only grants: `CREATE ROLE`, `GRANT SELECT, INSERT`, then `EXECUTE AS USER` → `UPDATE` refused with **error 229, SQLState S0005**. Spring translates it to `UncategorizedSQLException` (measured), not `BadSqlGrammarException` as on PostgreSQL | US-7.1 (replaces ADR-001 F9), ops diagnostics | Detect privilege denials by vendor code 229 (SQL Server) via `SQLException.getErrorCode()` |
| F11 | logstash-logback-encoder **8.1**: XML element `<jsonGeneratorDecorator class="…MaskingJsonGeneratorDecorator">`, code `encoder.setJsonGeneratorDecorator(…)`, and **a programmatic decorator must be `start()`ed** (NPE otherwise). In XML a `<value>` regex is **cut at a bare `,` or `}`**, which threw `PatternSyntaxException` at startup | US-7.3 (reverts ADR-001 F4), US-1.3 | Use the 8.1 names. Write a literal comma or closing brace in mask regexes as `\x2C` and `\x7D`. ADR-001 F5 (the message field needs value masks) still holds |
| F12 | Spring Security 6.5: an unauthenticated request to a `denyAll()` actuator endpoint gets **401** (Security 7 gave 403) | US-1.2 AC5 | AC: "Prometheus is refused (401 or 403) without credentials". Do not pin the code |
| F13 | Testcontainers **1.21.4** works with Docker Engine 29.9.2 (API 1.56). The SQL Server 2022 image starts in about 30–40 s after the first pull (about 1.5 GB) | R1 plan S0 item S2, risk R7 | Nightly CI runner needs Docker and the image cached. Developers do not need Docker for the fast tier |
| F14 | Lock, claim and `event_seq` behaviour are identical on both databases, once F3–F5 are applied | README §5.1 | None. This is the evidence that the H2 fast tier is a valid proxy for everything except locking itself |
| F15 | **H2 has no `JSON_VALUE`** (nor `OPENJSON`). `TOP (n)`, `DELETE TOP (n)`, `OFFSET … FETCH NEXT … ROWS ONLY` and `BIT` comparisons (`= 0`/`= 1`) all work in H2 MSSQL mode (checked with the H2 2.3.232 shell) | US-7.1, US-7.4, US-8.4, any query on JSON content | **No SQL reads inside a JSON column.** A field that must be filtered on is written to its own column at insert time (for example `run_event.step_id`, `event_type`). JSON columns are stored, returned and checked with `ISJSON`, never queried. Paging uses `ORDER BY … OFFSET … FETCH`; batches use `TOP (n)` |
| F16 | **SQL Server allows only one NULL in a unique index; H2 allows many, and has no filtered indexes** (`CREATE UNIQUE INDEX … WHERE` is a syntax error on H2) | US-4.7 (`(pipeline_code, idempotency_key)` with NULL keys), any nullable unique column | Nullable unique keys are vendor migrations: `sqlserver/` uses a filtered unique index `WHERE idempotency_key IS NOT NULL`, `h2/` a plain unique index. A `slow`-tier test inserts two runs without a key |

## Consequences

- **Support.** Boot 3.5.14 receives no further OSS patches. Either buy commercial support, or
  accept patch bumps within 3.5.x (3.5.15+) through the normal process. Plan the move to Boot 4.x
  as a separate decision: ADR-001's evidence shows the Boot 4.1 line works, and its PostgreSQL
  findings will need SQL Server equivalents.
- **Two-tier testing.**
  - **Fast tier:** every build, H2, no Docker, about 1.5 min.
  - **Nightly tier:** `-Pslow`, SQL Server 2022, about 2.5 min for the probe.

  Merge rules: the fast tier gates every merge, and a red nightly run blocks the next release
  candidate.
- **Rule 4 is a review gate.** Any `if (db == …)` outside `SqlDialect` fails code review and the
  ArchUnit rule.
- **ADR-001's PostgreSQL findings** are superseded:
  - F8 (`make_interval`) is replaced by `DATEADD`;
  - F9 (SQLState 42501) is replaced by vendor code 229;
  - F10 (Flyway 12) does not apply.

  Still valid from ADR-001: F5 (message masking), F6 (strict unknown-property handling, through
  F7 here), F11 (no nested repositories) and F12 (Docker).
- **ADR-002 (JSONata) is unaffected.** It has no Spring or database dependency.

## Follow-ups

1. Apply F1–F16 to the README (§5.1, §5.2, §10, §11, §13, §14), the affected stories and the R1
   plan.
2. Mark ADR-001 superseded.
3. ~~Tech lead acceptance at the Sprint 0 review.~~ Done 2026-11-05.
4. Support path (review decision 1): commercial support if production comes before a Boot 4 move, otherwise patch bumps within 3.5.x. Confirm which branch applies once the production date is set, and record the Boot 4 move as its own dated ADR.
