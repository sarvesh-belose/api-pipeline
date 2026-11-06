# apiorch deployment guide (R1)

How apiorch is published behind a load balancer, and the values each environment must record (US-1.4 AC7). It
covers R1: the HTTP API, health, metrics and graceful shutdown. Callbacks (R2) are listed where the load balancer
must already allow for them.

## 1. Topology

- Two or more apiorch nodes, each with an **API port** (8080) and a **management port** (9091), on one SQL Server
  2022 database.
- One load balancer in front, terminating TLS. It publishes **only the API port**. The management port is never
  published: health checks and Prometheus reach each node's management port directly.
- Each node needs a unique `apiorch.instance-id` (the default is the host name).

## 2. What the load balancer must do

| # | Requirement | Why | Story |
|---|---|---|---|
| L1 | Forward `/api/**` to every node's API port, with the path, query string, headers and body unchanged | the run API and, from R2, `POST /api/v1/callbacks/{runId}/{stepId}?t=…` with `X-Callback-Token` | US-1.4 AC1, AC3 |
| L2 | Send `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-For` | apiorch builds `Location` (and, from R2, callback URLs) from them | US-1.4 AC2 |
| L3 | Reach the nodes from addresses matched by `APIORCH_TRUSTED_PROXIES` | forwarded headers from any other address are ignored | US-1.4 AC2 |
| L4 | Health-check each node at `GET http://<node>:9091/actuator/health/readiness` and remove it from rotation when it is not `200` | a node turns readiness to `REFUSING_TRAFFIC` when shutdown begins | US-1.4 AC6, US-5.5 |
| L5 | Retry a request on another node when a node answers `503` with problem type `shutting-down` | the run was not created, so the retry is safe | US-5.5 AC7 |
| L6 | Do not publish `/actuator/**` | it lives on the management port only; on the API port it is `404` | US-1.4 AC5 |
| L7 | Allow a read timeout of at least the longest `syncWaitMs` (30 s by default) plus a margin | sync runs hold the request open while they run | US-4.1 |
| L8 | Allow shutdown to take up to `spring.lifecycle.timeout-per-shutdown-phase` (30 s) per node | in-flight steps get up to `apiorch.shutdown.grace-ms` (20 s) to finish | US-5.5 |

## 3. Per-environment values

Fill in one column per environment. Every value here is configuration, not a secret. Secrets (the JWT issuer's
keys, `APIORCH_SECRET_CTX_K1`, database credentials) come from the platform's secret store, never from this file.

| Value | Setting | Local simulation | R1 environment |
|---|---|---|---|
| Public base URL | what callers use | `https://apiorch.test.example.internal` (nginx on `http://localhost:8443`) | _to fill in_ |
| Load balancer health check | path, interval, failures to remove | `GET :9091/actuator/health/readiness`; nginx OSS has no active checks (see §4) | _to fill in_ |
| Trusted proxy addresses | `APIORCH_TRUSTED_PROXIES` (regex for `server.tomcat.remoteip.internal-proxies`) | `127\.0\.0\.1\|0:0:0:0:0:0:0:1` (Docker Desktop delivers nginx's traffic from loopback) | _to fill in_ (default `10\.\d+\.\d+\.\d+`) |
| Expected public host | `apiorch.callback.expected-public-host` (startup check of the callback base URL, R2) | not used (`local` profile) | _to fill in_ |
| Callback base URL | `apiorch.callback.public-base-url` (R2) | not used | _to fill in for R2_ |
| Allowed downstream hosts | `apiorch.endpoints.allowed-hosts` (empty allows no host) | `localhost` (`local` profile) | _to fill in_ (decided at deployment, security review §7 point 3) |
| Prometheus scrapers | `apiorch.security.metrics-allowed-cidrs` | default (`10.0.0.0/8`, loopback) | _to fill in_ |
| JWT issuer and audience | `APIORCH_JWT_ISSUER_URI`, `APIORCH_JWT_AUDIENCE` | dev mode (`local`) | _to fill in_ |
| Node count and instance ids | `apiorch.instance-id` | `node-a`, `node-b` | _to fill in_ |

## 4. Local simulation (2026-11-06)

`tools/lb-sim/` runs the checklist on one machine: two nodes from the built jar (`start-node.sh`, the `local`
profile with its dev security and demo seed) on SQL Server 2022 in Docker, behind nginx in Docker (`nginx.conf`),
with the demo WireMock from `docker compose up -d wiremock`. `GracefulStop.java` stops a node the way SIGTERM would
(through Spring Boot's shutdown endpoint over local JMX; the HTTP shutdown endpoint stays denied).

| Check | Result |
|---|---|
| AC1: a sync run through the load balancer equals one sent to a node: status, `X-Apiorch-*` headers, body | **pass** (`200`, same headers, `COMPLETED`); traffic reached both nodes (8 and 6 runs) |
| AC2: a `202` points at the public address | **failed at first:** run `Location` headers were relative (`/api/v1/runs/{id}`). Fixed: `RunController` now builds them from the request's public address (forwarded headers from trusted proxies only); then **pass**: `https://apiorch.test.example.internal/api/v1/runs/{id}`. `ForwardedHeadersIT` now covers the real run endpoint |
| AC3: a callback path reaches the app with its query and headers | **pass** for the routing (the same `404` problem with the same path through the load balancer and directly); the callback endpoint itself arrives in R2 |
| AC5: `/actuator/**` is not published | **pass**: `404` problem+json through the load balancer; readiness answers on 9091 |
| AC6: a node shutting down takes no new request and loses no run | **pass**: 150 requests at about 10/s while node A shut down, all `202`; node A released its last run with a wake-up and node B recovered it; all 165 runs ended `COMPLETED` |

**Limits of the simulation.** nginx OSS has no active health checks, so node removal (L4) was exercised through the
`503 shutting-down` retry (L5) instead; the R1 load balancer must probe readiness. TLS termination was simulated by
headers only. The "untrusted source is ignored" half of AC2 is covered by `ForwardedHeadersIT`, because on Docker
Desktop the simulated proxy and a direct caller both arrive from loopback.

## 5. Before go-live in the R1 environment

1. Fill in the R1 column of §3.
2. Run AC1, AC2, AC5 and AC6 against the real load balancer (the commands in §4 apply with the real addresses).
3. Confirm the health check removes a node within its interval when readiness turns `REFUSING_TRAFFIC` (L4).
