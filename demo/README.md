# apiorch reorder demo (US-8.1 slice A)

One inbound call orchestrates four downstream APIs. The business then changes the order of the steps, and
apiorch follows the new order on the next run, with no redeploy. The demo also shows a reorder that would break
a data dependency being refused, a flaky API being retried, and a failing API being cut off by its circuit
breaker. Everything runs on your machine: WireMock plays the downstream APIs, and apiorch runs on in-memory H2.

The async parts (a document step that calls back, and "complete while async pending") arrive with slice B in R2.

## Start

Needs Docker and JDK 17.

```bash
docker compose up -d wiremock
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Within a minute the app is ready on <http://localhost:8080>. The `local` profile seeds four endpoints
(`customer-svc`, `kyc-svc`, `score-svc`, `notify-svc`, all on WireMock at <http://localhost:8089>) and publishes
`DEMO_ONBOARD` v1. Security is in dev mode: every request is `dev-user` with all roles, so no token is needed.
Restarting is safe: the seed upserts the endpoints and publishes v1 only once.

## The pipeline

| Step | API | Call | Notes |
|---|---|---|---|
| `api1` | `customer-svc` | `POST /customers` | creates the customer; idempotent |
| `api2` | `kyc-svc` | `POST /kyc` | uses `steps.api1.body.id`; retried on 503 (3 attempts) |
| `api3` | `score-svc` | `GET /scores/{id}` | falls back to score 0 if it fails |
| `api5` | `notify-svc` | `POST /welcome` | uses the KYC reference; a failure is tolerated |

v1 runs `api1 → api2 → api3 → api5`. v2 (`src/main/resources/seed/DEMO_ONBOARD.v2-reordered.json`) runs
`api1 → api2 → api5 → api3`: notify the customer before scoring.

## Walkthrough

Run `demo/walkthrough.ps1` for a scripted version with pauses, or send the requests in `demo/requests.http` one
by one. Each step says what to look at. Start from a freshly started app: on a reused one, step 3 answers `409 no-changes` because v2 is
already published (the script still runs to the end, and resets KYC and its circuit first).

1. **Run v1.** `POST /api/v1/pipelines/DEMO_ONBOARD/runs?mode=sync` returns `200 COMPLETED` with the combined
   output `{customerId, kyc, score}`.
2. **Timeline.** `GET /api/v1/runs/{runId}/timeline` lists the inbound request, the four downstream calls in
   order with their durations and sizes, and the response apiorch sent back. Add `?include=events` for the full
   event log.
3. **Reorder.** Save `DEMO_ONBOARD.v2-reordered.json` as the draft and publish it. The next run reports
   `version: 2` and calls `/welcome` before `/scores/…`. Nothing was restarted. A run started before the publish
   would still finish on v1.
4. **A reorder that breaks a dependency.** `DEMO_ONBOARD.invalid-reorder.json` moves `api5` before `api2`, but
   `api5` reads `steps.api2.body.ref`. Publishing it is refused with `422` and `FORWARD_REFERENCE`.
5. **A flaky API.** Switch the KYC stub to "flaky" (two 503s, then 200) with
   `PUT http://localhost:8089/__admin/scenarios/kyc/state` and `{"state":"flaky"}`. The next run still
   completes; its timeline shows three attempts on `api2`.
6. **A failing API.** Switch the KYC stub to "down" (always 503). After five failing calls the circuit opens:
   runs then fail at once with `CIRCUIT_OPEN`, without calling KYC, and
   `GET /api/v1/admin/endpoints/kyc-svc/circuit` shows `OPEN`. Switch back with `{"state":"Started"}` and reset
   the circuit with `POST /api/v1/admin/endpoints/kyc-svc/circuit/reset` and `{"reason":"KYC back"}`.

The same walkthrough runs as `DemoWalkthroughIT` in the build, against these WireMock mappings, so the demo keeps
working.

## Notes

- No real secrets: the demo endpoints use no auth. Downstream URLs are `http://localhost:8089`, allowed only by
  the `local` profile's `allow-http` and `allowed-hosts`.
- Outside `local`, `apiorch.seed.enabled` is `false` and nothing is seeded.
- For production parity you can run SQL Server instead of H2: set `MSSQL_SA_PASSWORD`, run
  `docker compose --profile mssql up -d mssql wiremock`, then point `SPRING_DATASOURCE_URL`,
  `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` at it.
