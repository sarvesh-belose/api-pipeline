# US-1.1 Project scaffold & runtime baseline
**Epic:** E1 Foundation · **Priority:** Must · **Size:** M · **Depends on:** — · **Release:** R1

## User story
As a **Developer**, I want a runnable Spring Boot service skeleton with profiles, SQL Server
migrations, health probes, request size limits and a standard error format, so that every later
story builds on one consistent and deployable baseline, written in the SQL of the database it
will run on in production.

## Description
This story creates the `apiorch-service` Maven project in `D:\workspace_sv\api-pipeline` with:
- profiles `local`, `test` and `prod` (README §5.2, ADR-003): `local` and `test` on H2 2.3
  in-memory in `MODE=MSSQLServer` (no Docker), `prod` on SQL Server 2022. Real SQL Server also
  runs in the local `slow` tier through Testcontainers (ADR-003 rule 6, README §13);
- Flyway owning the schema (Hibernate `ddl-auto: none`), with one SQL text in SQL Server syntax,
  `common/` plus `{vendor}` migration folders (ADR-003 rules 1–3), and a migration test;
- actuator liveness and readiness probes on a separate management port;
- RFC 9457 problem+json errors;
- per-path request body limits returning `413`;
- OpenAPI docs;
- build quality gates.

**Out of scope:** security rules (US-1.2), the log format (US-1.3) and business features.

## Acceptance criteria
- [ ] **AC1:** Given JDK 17 and Maven 3.9, when a developer runs `mvnw verify` on a clean
  checkout, then the build compiles, runs unit and integration tests, and produces
  `target/apiorch-service-<version>.jar`.
- [ ] **AC2:** Given the `local` profile and no Docker, when the app starts with
  `mvnw spring-boot:run -Dspring-boot.run.profiles=local`, then it listens on `8080`, Flyway
  applies the `common/` and `h2/` migrations (including `h2/V0_1__h2_mssql_shims.sql`) to an H2
  in-memory database in `MODE=MSSQLServer`, and
  `GET http://localhost:9091/actuator/health/readiness` returns `200 {"status":"UP"}`.
- [ ] **AC3:** Given the `prod` profile with datasource environment variables, when the app
  starts, then Flyway migrates SQL Server (`common/` and `sqlserver/`) and readiness is `UP`.
  Given the database is unreachable, readiness reports `DOWN` while liveness stays `UP`.
- [ ] **AC4:** Given any API error (unknown route, malformed JSON, validation failure,
  unhandled exception), when the response is returned, then it is `application/problem+json`
  with `type` (`urn:apiorch:problem:<slug>`), `title`, `status`, `detail`, `instance` and
  `correlationId`. An unknown route returns `404` `not-found`, and unhandled exceptions return
  `500` without stack traces or class names.
- [ ] **AC5:** Given a request body larger than the limit for its path, when it arrives, then
  the response is `413` `payload-too-large` before the body is parsed. The limits are
  `apiorch.definition.max-document-bytes` for `/api/v1/admin/**`, `apiorch.run.max-input-bytes`
  for `/api/v1/pipelines/*/runs`, and `apiorch.callback.max-body-bytes` for
  `/api/v1/callbacks/**`. Chunked bodies without `Content-Length` are counted while reading.
- [ ] **AC6:** Given every Flyway migration, when `MigrationIT` runs, then all migrations apply in
  order to an empty H2 database (fast tier) and to an empty Testcontainers SQL Server 2022 (`slow`
  tier), every JPA entity can be saved and read back, and `flyway:validate` reports no drift. An
  `Instant` written and read back with the JVM default zone set to a non-UTC zone (for example
  `Asia/Kolkata`) is unchanged on both databases (ADR-003 F6, rule 5). The `slow` tier also
  asserts that each declared partitioned table (README §10) puts the current month in its own
  partition (ADR-003 F9); H2 has no partitioning (ADR-003 F8).
- [ ] **AC7:** Given the `local` profile, when a developer opens `/swagger-ui.html`, then the
  OpenAPI UI lists every controller. With `prod`, the API docs are disabled unless
  `springdoc.api-docs.enabled=true`.
- [ ] **AC8:** Given SIGTERM, when in-flight HTTP requests exist, then the server stops
  accepting requests, readiness turns `DOWN`, and in-flight requests get up to
  `spring.lifecycle.timeout-per-shutdown-phase` (30 s) to finish.
- [ ] **AC9:** Given the build, when `mvnw verify` runs, then JaCoCo enforces at least 80% line
  coverage on `com.crisil.apiorch` (excluding config and DTO classes), **and at least 85% branch
  coverage on `engine`, `expression`, `http`, `callback` and `outbox`**, and fails the build below
  either.
- [ ] **AC10 (test tiers):** Given the build, when `mvnw verify` runs, then surefire runs `*Test`
  and failsafe runs `*IT` **excluding** the `slow` tag, and `mvnw verify -Pslow` runs the slow tier.
  A test tagged `slow` therefore never runs in the everyday `mvnw verify` build (README §13).
- [ ] **AC11 (architecture rules):** Given `ArchUnitRulesTest`, when it runs, then it fails on: a
  class in `engine`, `expression` or `definition` referencing an HTTP client, `JdbcClient` or a
  repository; an HTTP client call inside a `@Transactional` method; a write to `pipeline_run`
  outside `JdbcClient`; and any use of `Instant.now()`, `LocalDateTime.now()` or
  `System.currentTimeMillis()` outside the allow-listed `Clock` provider; any import of
  `tools.jackson` from product code (only Jackson 2, `com.fasterxml.jackson`, is on this line,
  ADR-003 F7); any database-vendor branching outside `SqlDialect` (ADR-003 rule 4); and any
  import of the `…support.h2` shim package from engine code (ADR-003 rule 3). Each rule has a
  deliberately violating fixture in test scope proving the rule fires.
- [ ] **AC12 (strict JSON):** Given any typed request body (a record bound with `@RequestBody`)
  that contains an unknown property, for example `"dependOn"` or an extra `"password"` field,
  when it is posted, then the response is `400` `validation-failed` naming the property. Boot's
  auto-configured `ObjectMapper` disables `FAIL_ON_UNKNOWN_PROPERTIES` (ADR-003 F7), so this is
  switched on with
  `spring.jackson.deserialization.fail-on-unknown-properties=true`, and a test proves the setting
  is in effect. Free-form bodies, such as the run input (US-4.1) and callback bodies (US-6.2), are
  `JsonNode`/`Map` and unaffected.

## Technical details
**Project layout**
```
api-pipeline/
  pom.xml  mvnw  mvnw.cmd  .mvn/wrapper/  docker-compose.yml  .gitignore  .editorconfig
  src/main/java/com/crisil/apiorch/ApiorchApplication.java
  src/main/java/com/crisil/apiorch/config/          (ApiorchProperties, WebConfig, RequestSizeLimitFilter)
  src/main/java/com/crisil/apiorch/observability/   (GlobalExceptionHandler, ProblemTypes)
  src/main/resources/application.yml, application-local.yml, application-test.yml, application-prod.yml
  src/main/java/com/crisil/apiorch/support/h2/      (H2Functions: SYSUTCDATETIME shim, never imported by engine code)
  src/main/resources/db/migration/common/V1__baseline.sql
  src/main/resources/db/migration/h2/V0_1__h2_mssql_shims.sql
  src/main/resources/db/migration/sqlserver/        (ISJSON checks, partitioning, roles and grants)
  src/test/java/com/crisil/apiorch/...              (*Test = unit/surefire, *IT = integration/failsafe)
```
**pom.xml**
- `spring-boot-starter-parent` **3.5.14** (ADR-003), `maven.compiler.release=17`,
  groupId `com.crisil`, artifactId `apiorch-service`. `spring-cloud-dependencies` **2025.0.3**
  is imported in `dependencyManagement` (ADR-003). No R1 story needs a Spring Cloud module since
  US-1.4 dropped the service registry; the import is harmless and kept until the tech lead decides.
- **Dependencies** (ADR-003 "Starter and module names on Boot 3.5"):
  - Boot starters: `spring-boot-starter-web`, `-validation`, `-data-jpa` and `-actuator`.
    There is no Flyway starter and no `-restclient` starter on Boot 3.5; `RestClient` comes with
    `spring-web`.
  - Database: `flyway-core` plus `flyway-sqlserver` (Flyway 11.7), `mssql-jdbc` (runtime) and
    `h2` 2.3.232 (runtime, for `local` and `test`).
  - API docs: `springdoc-openapi-starter-webmvc-ui` **2.8.17**, the line that supports Boot 3.5.
  - `spring-boot-configuration-processor`.
- **Test dependencies:** `spring-boot-starter-test` (JUnit 5.12); the Testcontainers 1.21.4
  modules `org.testcontainers:mssqlserver` and `junit-jupiter`; `org.wiremock:wiremock-standalone`;
  `org.awaitility:awaitility`; `com.tngtech.archunit:archunit-junit5`.
- **Scaffold check: done, see [ADR-003](../../docs/adr/ADR-003-boot35-sqlserver.md) (gate G1
  re-run, supersedes ADR-001).** Boot 3.5.14 with Spring Cloud 2025.0.3 was built and run in
  [`tools/stack-probe-b35-mssql/`](../../tools/stack-probe-b35-mssql/) with every load-bearing
  library: Jackson 2.21, springdoc 2.8.17, logstash-logback-encoder 8.1, the Resilience4j 2.4.0
  Micrometer bridge, ShedLock 6.10.0, Testcontainers 1.21.4, `eureka-client` 2.0.6, ArchUnit
  1.5.1 and JaCoCo 0.8.16. 24 of 24 probe tests pass (fast tier on H2 plus the `slow` tier on SQL
  Server 2022). The scaffold copies ADR-003's versions into one `<properties>` block, and changing
  any pinned version requires re-running the probe. The consequences carried into this story:
  - strict JSON is switched on explicitly (AC12, ADR-003 F7);
  - Jackson 3 (`tools.jackson`) is fenced off by ArchUnit (AC11, ADR-003 F7);
  - `SYSUTCDATETIME()` is shimmed on H2 (ADR-003 F2, rule 3) and `Instant` mapping is pinned
    (ADR-003 F6, rule 5);
  - Boot 3.5's open-source support ended 2026-06-30; the owner accepted this, and CVE patches
    after 3.5.14 need a commercial subscription or a patch bump (ADR-003 F1).
- **Plugins:** surefire (`*Test`), failsafe (`*IT`, with `excludedGroups=slow`; profile `slow`
  inverts it to `groups=slow`), and jacoco with two check rules: line ≥ 0.80 overall and
  branch ≥ 0.85 on the engine packages (AC9).

**Configuration (application.yml)**
```yaml
spring:
  application.name: apiorch-service
  mvc.problemdetails.enabled: true
  jpa:
    open-in-view: false
    hibernate.ddl-auto: none
    properties.hibernate:
      jdbc.time_zone: UTC
      type.preferred_instant_jdbc_type: TIMESTAMP   # Hibernate 6.6 ignores time_zone otherwise (ADR-003 F6)
  jackson.deserialization.fail-on-unknown-properties: true   # Boot's ObjectMapper sets it false (ADR-003 F7)
  flyway: { locations: "classpath:db/migration/common,classpath:db/migration/{vendor}" }
  datasource.hikari.maximum-pool-size: 80   # sized with step-io, README §14; never the default 10
  lifecycle.timeout-per-shutdown-phase: 30s
  mvc.async.request-timeout: 35s
server: { port: 8080, shutdown: graceful }
management:
  server.port: 9091
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true
  endpoint.health.group.readiness.include: readinessState,db
```
- `application-local.yml` and `application-test.yml` use H2 in-memory
  (`jdbc:h2:mem:apiorch;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1`,
  ADR-003 rule 1), so Flyway resolves `{vendor}` to `h2`. `local` uses a small Hikari pool and
  turns on the springdoc UI.
- `application-prod.yml` reads `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` for SQL Server 2022
  from the environment, so Flyway resolves `{vendor}` to `sqlserver`. Production JVMs also run with
  `-Duser.timezone=UTC` as a second guard, but correctness does not depend on it (ADR-003 rule 5).
- `ApiorchProperties` (`@ConfigurationProperties("apiorch")`, `@Validated`) is the single typed
  home for every key in README §11. Startup fails on invalid values.

**Schema conventions** (README §10, ADR-003 rules 1–2): ids are `UNIQUEIDENTIFIER`, timestamps
are `DATETIME2(6)` holding UTC, flags are `BIT` (compared with `= 1`/`= 0`), and payloads and JSON
documents are `NVARCHAR(MAX)`. JSON columns carry an `ISJSON` check in `sqlserver/` migrations
only (ADR-003 F8) and are never queried with `JSON_VALUE`; a field that must be filtered on gets
its own column (ADR-003 F15). The four high-volume tables are month-partitioned in `sqlserver/`
(ADR-003 F9); `h2/` holds plain equivalents with the same version numbers. Hibernate never
generates or validates DDL; `MigrationIT` is the guard. Every migration follows the
expand/contract rule in README §10, so a rolling restart is always safe. `common/V1__baseline.sql`
creates `shedlock(name VARCHAR(64) PRIMARY KEY, lock_until DATETIME2(6), locked_at DATETIME2(6),
locked_by VARCHAR(255))`. `h2/V0_1__h2_mssql_shims.sql` runs first on H2 and defines
`CREATE ALIAS SYSUTCDATETIME FOR '…support.h2.H2Functions.sysUtcDateTime'`, because H2 2.3 has no
`SYSUTCDATETIME()` (ADR-003 F2, rule 3).

**Request size:** `config.RequestSizeLimitFilter` is a servlet filter ordered first. It maps
path patterns to limits, rejects on `Content-Length`, and otherwise wraps the input stream with
a counting limit. JSON bodies are not covered by any built-in Tomcat or servlet property.

**docker-compose.yml:** the app needs no database service for local runs, because `local` runs on
H2 in-memory. An optional service `mssql` (`mcr.microsoft.com/mssql/server:2022-latest`, port
1433, `ACCEPT_EULA=Y`) is there for developers who want production parity; they point the
datasource environment variables at it. US-8.1 adds `wiremock`.

**Errors:** `GlobalExceptionHandler` (`@RestControllerAdvice`, extends
`ResponseEntityExceptionHandler`) maps exceptions to `ProblemDetail`. `ProblemTypes` holds the
slugs from README §9. `NoResourceFoundException` maps to `404 not-found`, and every problem
carries `correlationId` (US-1.3).

**Test notes**
- Two tiers (ADR-003 Consequences): the fast tier runs on H2 with no Docker and gates every
  commit; the local `slow` tier (`./mvnw verify -Pslow`, before merge and before each sprint
  review) runs on SQL Server 2022 through Testcontainers
  (`MSSQLServerContainer`, `.acceptLicense()`, `@ServiceConnection`). Anything about locking,
  concurrency, leases, partitions, grants or `ISJSON` is proven in the `slow` tier (ADR-003 rule 6).
- `ApplicationSmokeIT` (H2) covers AC2 and AC3's readiness/liveness split when the database is
  unreachable; a `slow`-tagged variant starts against SQL Server.
- `MigrationIT` covers AC6 on H2; `MigrationSqlServerIT` (`slow`) covers it on SQL Server,
  including the partition assertion and the non-UTC `Instant` round trip.
- `ProblemDetailsIT` covers AC4.
- `RequestSizeLimitIT` covers Content-Length and chunked bodies for each path (AC5).
- `GracefulShutdownIT` covers AC8.
- `ArchUnitRulesTest` covers AC11, with one violating fixture per rule, including a class that
  imports `tools.jackson.databind.ObjectMapper`, a vendor branch outside `SqlDialect`, and an
  engine class importing `…support.h2`.
- `StrictJsonBindingIT` posts a typed body with an unknown property and expects `400`, and
  asserts that the Boot-managed `ObjectMapper` has `FAIL_ON_UNKNOWN_PROPERTIES` enabled (AC12).
- `TestTieringTest` asserts that every `*IT` carries either no tag or `slow`, so a new slow test
  cannot silently join the everyday build (AC10).

**Clock:** `config.TimeConfig` exposes a single `Clock` bean (`Clock.systemUTC()` in production, a
`MutableClock` in tests). Every component takes it by constructor. Database time
(`SYSUTCDATETIME()`, shimmed on H2) remains the authority for leases, ShedLock and retention cutoffs, and the
`Clock` is never used for those (README §13).
