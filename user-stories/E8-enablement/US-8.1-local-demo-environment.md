# US-8.1 Local demo environment with stubbed APIs and reorder walkthrough
**Epic:** E8 Enablement · **Priority:** Must · **Size:** M · **Depends on:** US-4.6 (R1 slice), US-6.3 (full) · **Release:** **R1 (slice A) / R2 (slice B)**

## User story
As a **Developer**, and to demo the product to the **Business Owner**, I want a one-command local
environment with five stubbed downstream APIs (one of them an async step with a real callback),
a seeded demo pipeline and a scripted walkthrough, so that anyone can see sequencing, mapping,
async completion, resilience and a no-redeploy reorder working end to end in minutes.

## Description
The demo has three parts:
- **`docker compose up -d wiremock`** starts **WireMock** with mappings for `customer-svc`,
  `kyc-svc`, `score-svc`, `doc-svc` (an async step that acknowledges with 202 and calls back
  after 3 s) and `notify-svc`. The app needs no database container: the `local` profile uses
  in-memory H2 in `MODE=MSSQLServer` (ADR-003). An optional `mssql` Compose service (SQL Server
  2022, profile `mssql`) is there for production parity, started with `docker compose --profile
  mssql up -d mssql wiremock`.
- **The `local` profile** runs the app on in-memory H2 (the same Flyway migrations, `common/` plus
  `h2/`) with `apiorch.seed.enabled=true` and dev security.
  It seeds the five endpoints and publishes `DEMO_ONBOARD` v1 (`api1 → api2 → api3 → api4 →
  api5`) through the normal services, so the seed passes validation.
- **The walkthrough** is `demo/README.md`, `demo/requests.http` and `demo/walkthrough.ps1`. It
  covers: a run, its timeline, the reorder to `1→2→5→4→3`, an invalid reorder that is rejected,
  a 503 burst that is retried, an open circuit, and complete-while-async-pending.

**Networking:** the WireMock container must reach the app on the host for callbacks. The
`local` profile therefore sets `apiorch.callback.public-base-url=http://host.docker.internal:8080`,
and compose adds `extra_hosts: ["host.docker.internal:host-gateway"]` for Linux hosts.

**Delivered in two slices.** The reorder walkthrough is what proves the headline requirement to the
Business Owner, and proving it at the end of R2 is too late — the sponsor should be able to watch it
work from the first milestone, and the team gets a continuous demo instead of a demo it builds once.
The async parts simply cannot exist before E6, so the story splits along that line:

| Slice | Release | Contents |
|---|---|---|
| **A — the reorder demo** | **R1**, after US-4.6 | Compose (`wiremock`; optional `mssql`); four **sync** stubs (`customer-svc`, `kyc-svc`, `score-svc`, `notify-svc`); `LocalSeedRunner`; `DEMO_ONBOARD` v1 as `api1→api2→api3→api5`; the walkthrough's run, timeline, **reorder**, rejected-reorder, 503-burst and open-circuit steps. AC1, AC3 (without api4), AC4, AC5, AC6 |
| **B — the async demo** | R2, after US-6.3 | The `doc-svc` async stub with its 3 s callback, api4 added to the pipeline, `host.docker.internal` wiring, and complete-while-async-pending. AC2 and the api4 half of AC3 |

Slice A needs nothing from E5 beyond US-5.3, and nothing from E6. The seed files are written so
that adding api4 in slice B is a new published version, not an edit to slice A's fixtures — which
makes the slice boundary itself a demonstration of the no-redeploy reorder.

**Out of scope:** production deployment manifests.

## Acceptance criteria
- [ ] **AC1:** Given Docker and JDK 17, when a developer runs `docker compose up -d wiremock`
  and then `mvnw spring-boot:run -Dspring-boot.run.profiles=local`, then within 60 s the app is
  ready, five endpoints exist, and `DEMO_ONBOARD` v1 is published. The seed is idempotent, so a
  restart neither duplicates data nor fails (in-memory H2 starts empty; against the optional
  `mssql` service the data persists and the upsert keeps it single).
- [ ] **AC2:** Given the seeded demo, when the walkthrough sends
  `POST /api/v1/pipelines/DEMO_ONBOARD/runs?mode=sync` with the sample customer, then the
  response is `200` with `status: "COMPLETED_PENDING_ASYNC"`, because api4 `doc-svc` is async and
  non-blocking and api5 is a post-completion step. About 3 s later the WireMock callback reaches
  the app through `host.docker.internal`. `GET /api/v1/runs/{id}` then shows `COMPLETED`, and the
  timeline lists the inbound request, 5 requests, 1 callback and the caller responses, with
  durations and sizes.
- [ ] **AC3:** Given the walkthrough's reorder step, when it publishes v2 with order `api1, api2,
  api5, api4, api3`, then validation passes, with the warning `STEP_RUNS_AFTER_COMPLETION` for
  api3. A new run follows the new order with no restart. Publishing the broken variant (api3
  before api2 while reading `steps.api2.body.ref`) gets `422` with `FORWARD_REFERENCE`.
- [ ] **AC4:** Given the resilience step, when it switches the WireMock `kyc-svc` scenario to
  "flaky" (503, 503, 200) through the WireMock admin API, then the next run succeeds with 3
  attempts on api2, which is seeded `idempotent: true`. Switched to "down" (always 503), the
  following runs show `CIRCUIT_OPEN` attempts after the threshold, and
  `GET /api/v1/admin/endpoints/kyc-svc/circuit` shows `OPEN`.
- [ ] **AC5:** Given the seed data, then no real secrets are used. Endpoints use `auth: NONE`,
  or `API_KEY` with `env:APIORCH_SECRET_DEMO_API_KEY` defaulted in `application-local.yml`. URLs
  point to `http://localhost:8089`, allowed by `allow-http` and `allowed-hosts: localhost` in
  `local`.
- [ ] **AC6:** Given `apiorch.seed.enabled=false` (the default outside `local`), when the app
  starts, then nothing is seeded.

## Technical details
**Files**
```
docker-compose.yml                      wiremock/wiremock:3.x (port 8089, --global-response-templating,
                                        extra_hosts host-gateway); optional mssql
                                        (mcr.microsoft.com/mssql/server:2022-latest, profile mssql)
demo/wiremock/mappings/*.json           customer-svc, kyc-svc (scenarios normal|flaky|down), score-svc, doc-svc, notify-svc
demo/wiremock/__files/*.json            response bodies
demo/README.md                          walkthrough narrative
demo/requests.http                      REST-client requests for every step
demo/walkthrough.ps1                    scripted walkthrough with pauses
src/main/resources/seed/endpoints-local.json
src/main/resources/seed/DEMO_ONBOARD.json                  (v1)
src/main/resources/seed/DEMO_ONBOARD.v2-reordered.json
src/main/resources/seed/DEMO_ONBOARD.invalid-reorder.json
```
**WireMock `doc-svc` async step stub:** returns `202 {"jobId":"{{randomValue type='UUID'}}"}`.
A `serveEventListeners` webhook then POSTs `{{originalRequest.headers.X-Callback-Url}}` after
`fixedDelay 3000`, with body `{"status":"DONE","docId":"D-{{randomValue length=6
type='NUMERIC'}}"}`.

**DEMO_ONBOARD v1** (`allowedCallers: ["dev-user"]`)
| Step | Endpoint | Call | Notes |
|---|---|---|---|
| `api1` | `customer-svc` | `POST /customers` | `idempotent: true` |
| `api2` | `kyc-svc` | `POST /kyc` | `idempotent: true`; body uses `steps.api1.body.id` |
| `api3` | `score-svc` | `GET /scores/{steps.api1.body.id}` | `onFailure: FALLBACK`, `fallback: "{ 'score': 0 }"` |
| `api4` | `doc-svc` | `POST /documents` | async, `blocksRunCompletion: false`, `onFailure: CONTINUE`; body uses `steps.api1.body.id` and `callback.url` |
| `api5` | `notify-svc` | `POST /welcome` | `onFailure: CONTINUE`; post-completion in v1 |

The dependencies are chosen so that the reorder `1→2→5→4→3` is valid. api3 becomes
post-completion in v2, which `FALLBACK` permits.

**Components:** `config.LocalSeedRunner` is an `ApplicationRunner` active when
`apiorch.seed.enabled=true`. It upserts the endpoints by name, and publishes the pipeline only
if no version exists.

**Configuration:** `apiorch.seed.enabled` (`false`; `true` in `application-local.yml`), plus the
`local` values for `apiorch.callback.public-base-url`, `apiorch.callback.keys.local`
(`env:APIORCH_SECRET_CALLBACK_LOCAL`, defaulted) and `apiorch.context.encryption-keys` (empty,
so plain mode with a WARN, US-7.6).

**Test notes**
- `LocalSeedIT` covers seeding, idempotent restart and disabled seeding (AC1, AC6).
- `DemoWalkthroughIT` is tagged `demo` and uses Testcontainers WireMock with
  `withExtraHost("host.docker.internal", "host-gateway")`. It runs the `requests.http` scenarios
  (AC2–AC4). In slice A it runs the sync scenarios only; the async case is added with slice B.
- Run the PowerShell script manually before each demo.
