# apiorch-service

Configurable, resilient API orchestration: one inbound API runs a versioned pipeline of downstream
calls (sequential, DAG, parallel, async with callback), with full activity logging.

| Where | What |
|---|---|
| [`user-stories/`](user-stories/README.md) | Backlog, architecture, concurrency rules, data model, config catalog, Definition of Done |
| [`docs/R1-implementation-plan.md`](docs/R1-implementation-plan.md) | Sprint 0 and Sprints 1–6 |
| [`docs/adr/`](docs/adr/) | Decisions. Current stack: [ADR-003](docs/adr/ADR-003-boot35-sqlserver.md) |
| [`tools/`](tools/README.md) | Sprint 0 gate probes (evidence, not product code) |

## Stack
JDK 17 · Spring Boot 3.5.14 · Spring Cloud 2025.0.3 · SQL Server 2022 in production · H2 2.3 in
`MODE=MSSQLServer` for local runs and the fast test tier (ADR-003).

## Build and run

There is no CI server: every build and test runs on a developer machine (README §13 of the
backlog). Run the fast tier before every commit, and the slow tier before merging a story with
`slow` tests and before each sprint review.

Fast tier (unit + integration on H2, no Docker):
```bash
./mvnw verify
```

Slow tier (adds the `slow`-tagged tests on Testcontainers SQL Server 2022; needs Docker):
```bash
./mvnw verify -Pslow
```

Run locally on in-memory H2, with the API on 8080 and the management endpoints on 9091:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Readiness: `http://localhost:9091/actuator/health/readiness`. API docs:
`http://localhost:8080/swagger-ui.html`.

Behind a corporate proxy, Maven needs the proxy in `~/.m2/settings.xml` (it does not read
`HTTPS_PROXY`).
