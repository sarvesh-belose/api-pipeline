# tools: Sprint 0 gate probes

These are evidence tools, **not product code**. Each probe proves an ADR at runtime. Re-run the
relevant probe whenever the thing it pins changes, and update its ADR with the result.

| Probe | Proves | Re-run when |
|---|---|---|
| [`stack-probe-b35-mssql/`](stack-probe-b35-mssql/) | [ADR-003](../docs/adr/ADR-003-boot35-sqlserver.md) (**current**): Spring Boot 3.5.14 + Spring Cloud 2025.0.3 works with every load-bearing library, and one set of SQL Server migrations and engine SQL runs on H2 (MSSQL mode) and on SQL Server 2022, with the `SqlDialect` differences proven | The Boot or Spring Cloud version, any version in its `<properties>` block, the H2 or mssql-jdbc version, or `SqlDialect` changes. Also before any JDK 21 move |
| [`stack-probe/`](stack-probe/) | [ADR-001](../docs/adr/ADR-001-stack.md) (superseded): Spring Boot 4.1.2 + Spring Cloud 2025.1.4 on PostgreSQL 16 | Kept as the starting point for a future Boot 4 upgrade decision. It does not reflect the production database |
| [`expression-probe/`](expression-probe/) | [ADR-002](../docs/adr/ADR-002-expression-engine.md): JSONata's bounds, thread-safety, numbers, errors and hostile-expression behaviour, plus the guards | The `com.dashjoin:jsonata` version changes, or `apiorch.expression.timeout-ms` is lowered |

## Prerequisites
- JDK 17 (`JAVA_HOME`) and Maven 3.9.
- **`stack-probe-b35-mssql` fast tier:** no Docker. It runs on in-memory H2.
- **`stack-probe-b35-mssql -Pslow` and `stack-probe`:** a running Docker engine. Testcontainers
  starts `mcr.microsoft.com/mssql/server:2022-latest` (about 1.5 GB on first pull) or
  `postgres:16-alpine`. On Windows, start Docker Desktop first; with the engine stopped, the
  integration tests fail with "Could not find a valid Docker environment" (ADR-001 F12).

## Run

Stack probe (current), fast tier: about 1.5 minutes, 15 tests (4 unit, 6 H2 contract, 5 web):
```bash
mvn -B -ntp -f tools/stack-probe-b35-mssql/pom.xml verify
```

With the SQL Server tier: about 2.5 minutes after the image is pulled, 24 tests:
```bash
mvn -B -ntp -f tools/stack-probe-b35-mssql/pom.xml verify -Pslow
```

Database findings are printed as `G3|<db>|…` lines. To collect them:
```bash
mvn -B -ntp -f tools/stack-probe-b35-mssql/pom.xml verify -Pslow | grep "G3|"
```

Superseded Boot 4.1 probe, about 2 minutes, 12 tests:
```bash
mvn -B -ntp -f tools/stack-probe/pom.xml verify
```

Expression probe, about 30 seconds, 10 tests:
```bash
mvn -B -ntp -f tools/expression-probe/pom.xml test
```

Expression findings are printed as `G2|…` lines. To collect them:
```bash
mvn -B -ntp -f tools/expression-probe/pom.xml test | grep "G2|"
```

## Reading the results
- **`stack-probe-b35-mssql`:** green means the pairing and the database contract hold. In the
  `G3|` lines, check the following against ADR-003:
  - `lock-blocks|dialect form` must read `blocked then timed out` on **both** databases.
  - `lock-blocks|WITH (UPDLOCK, ROWLOCK)` reads `NOT BLOCKED` on H2. That is expected (F3), and it
    is why the dialect uses `FOR UPDATE` there.
  - `roundtrip|` timestamps must match `jvmNow` even when the JVM is not on UTC (F6).
  - A context failure at startup usually means a library and Boot mismatch, or a migration that
    one of the two databases rejects.
- **`stack-probe`:** green means the Boot 4.1 pairing holds. A `SpringBootVersionVerifier`
  failure means the Spring Cloud train doesn't support the Boot line.
- **`expression-probe`:** the assertions only guard the harness, so check the `G2|` lines
  against ADR-002:
  - every `hostile|` line should read `ABORTED_BY_BOUNDS`, except H7 (`$pad`, expected
    `NOT_STOPPED` without the guard), H9 (regex) and H10 (`REJECTED_AT_COMPILE`);
  - `guard|` lines show that the guards close those gaps;
  - `race|` lines must show `wrong-or-failed=0` at the configured budget. Set the system property
    `g2.timeoutMs` in the surefire `argLine` to test another budget. Values well below 1000 ms
    produce spurious aborts under load, and that is expected.
