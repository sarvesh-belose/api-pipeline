# US-2.1 Register & manage downstream endpoints
**Epic:** E2 Downstream endpoints · **Priority:** Must · **Size:** M · **Depends on:** US-1.2 · **Release:** R1

## User story
As a **Pipeline Administrator**, I want to register each downstream system once, with its
base URL, timeouts and resilience settings, and refer to it by name in pipelines, so that
infrastructure details live in one place and can change without editing or redeploying any
pipeline.

## Description
An **endpoint** is a named downstream system, such as `customer-svc` or `kyc-svc`. Pipeline
steps reference it by `name` and add only `method` and `path`. An endpoint holds:
- base URL (restricted to allowed hosts) and an optional health path;
- connect and read timeouts, and HTTP version;
- resilience settings: circuit breaker, bulkhead and retry defaults (US-5.2 to 5.4);
- auth (US-2.2).

Endpoint settings are **not pinned per run**. A change applies to the **next attempt** on every
node within the refresh interval, including attempts of runs already in flight. This is
deliberate, because a base URL move must take effect everywhere.

**Out of scope:** auth types and secrets (US-2.2), change history (US-2.3), and circuit status
and reset (US-5.3).

## Acceptance criteria
- [ ] **AC1:** Given an admin, when they `POST /api/v1/admin/endpoints` with a valid body, then
  the response is `201` with `Location: /api/v1/admin/endpoints/{name}` and an `ETag` of the row
  version. `created_by` is the JWT principal.
- [ ] **AC2:** Given an invalid body, when it is posted, then `400` `validation-failed` lists
  every field error. Invalid means any of these:
  - a name that doesn't match `^[a-z][a-z0-9-]{1,62}$`;
  - a base URL that is not absolute, has userinfo, a query or a fragment, or uses `http://`
    while `apiorch.endpoints.allow-http=false`;
  - a host not matching `apiorch.endpoints.allowed-hosts`;
  - an IP-literal host outside `local`;
  - a `healthPath` that doesn't start with `/`, or contains `@`, `//`, `..`, `?` or `#`;
  - a value outside the ranges table below;
  - an unknown property, for example a misspelt `resilence`. This needs strict binding (US-1.1
    AC12), because Boot's auto-configured `ObjectMapper` disables `FAIL_ON_UNKNOWN_PROPERTIES` and
    would otherwise drop the field silently and save the endpoint with the default values
    (ADR-003 F7).

  A duplicate name returns `409` `already-exists`.
- [ ] **AC3:** Given an existing endpoint, when an admin sends
  `PUT /api/v1/admin/endpoints/{name}` with `If-Match: "<version>"`, then it is saved and the
  version increments. A stale `If-Match` returns `409` `version-conflict`, and a missing one
  returns `428` `precondition-required`.
- [ ] **AC4:** Given an endpoint is updated (for example its base URL or read timeout), when the
  next attempt to it starts on **any** node, then the new settings apply within
  `apiorch.endpoints.refresh-interval-ms` (10 s). This includes attempts of runs pinned to older
  pipeline versions. Calls already in flight finish with the old settings.
- [ ] **AC5:** Given an endpoint referenced by any of the following, when an admin sends
  `DELETE /api/v1/admin/endpoints/{name}`, then the response is `409` `endpoint-in-use`, listing
  each reference. Otherwise the response is `204`.
  - the latest published version of a pipeline;
  - a pipeline's draft;
  - any pipeline version used by a run that hasn't reached a terminal status, including
    `compensate.endpoint`.

  Setting `enabled=false` instead makes new attempts fail fast with step error code
  `ENDPOINT_DISABLED`: no request is sent and no `step_attempt` row is written.
- [ ] **AC6:** Given `healthPath: "/actuator/health"`, when an admin sends
  `POST /api/v1/admin/endpoints/{name}/probe`, then apiorch performs `GET` on the URI built
  segment by segment from `baseUrl` and `healthPath`, using the endpoint's timeouts and auth.
  It returns `200 {"reachable":true,"httpStatus":200,"durationMs":42}`, or `reachable:false`
  with the error class. The probe bypasses the circuit breaker, retry and bulkhead.
- [ ] **AC7:** Given `GET /api/v1/admin/endpoints[/{name}]` by `apiorch.admin` or
  `apiorch.ops`, when called, then the effective configuration is returned. Auth secrets appear
  only as references (US-2.2).

## Technical details
**API contract:** `POST /api/v1/admin/endpoints` / `PUT …/{name}`
```json
{
  "name": "kyc-svc",
  "description": "KYC verification (DC-2)",
  "baseUrl": "https://kyc.internal.example.com/api",
  "healthPath": "/health",
  "connectTimeoutMs": 2000,
  "readTimeoutMs": 5000,
  "httpVersion": "HTTP_1_1",
  "enabled": true,
  "auth": { "type": "NONE" },
  "resilience": {
    "circuitBreaker": { "slidingWindowSize": 20, "minimumCalls": 10, "failureRateThreshold": 50,
                        "slowCallDurationMs": 3000, "slowCallRateThreshold": 80,
                        "waitInOpenMs": 30000, "halfOpenCalls": 3 },
    "bulkhead": { "maxConcurrentCalls": 25, "maxWaitMs": 0 },
    "retry": { "maxAttempts": 3, "backoff": "EXPONENTIAL_JITTER", "initialMs": 200, "maxMs": 2000 }
  }
}
```
Responses return the **effective** values, with `"inherited": true` on sub-objects taken from
`apiorch.resilience.defaults.*`, plus `version`, `createdAt/By` and `updatedAt/By`.

**Allowed ranges**
| Field | Range |
|---|---|
| `connectTimeoutMs`, `readTimeoutMs` | 100 – 120 000 |
| `circuitBreaker.slidingWindowSize` / `minimumCalls` | 5 – 1 000, with `minimumCalls ≤ slidingWindowSize` |
| `failureRateThreshold`, `slowCallRateThreshold` | 1 – 100 |
| `slowCallDurationMs` | 100 – 120 000 |
| `waitInOpenMs` | 1 000 – 600 000 |
| `halfOpenCalls` | 1 – 50 |
| `bulkhead.maxConcurrentCalls` | 1 – 500 |
| `bulkhead.maxWaitMs` | 0 – 1 000 (0 recommended; retry backoff handles waiting) |
| `retry.maxAttempts` | 1 – 10 |
| `retry.initialMs` ≤ `retry.maxMs` | 10 – 60 000 |

**Data:** Flyway `V2__endpoint.sql` creates table `endpoint` (README §10). `auth_config` and
`resilience_config` are JSON text, `row_version` is the JPA `@Version`, and `name` is UNIQUE.

**Components** (`com.crisil.apiorch.endpoint`, `com.crisil.apiorch.http`)
- `EndpointController`, `EndpointService`, `EndpointEntity`, `EndpointRepository`; and
  `EndpointConfig`, an immutable record with the effective defaults.
- `HostPolicy` checks hosts against `apiorch.endpoints.allowed-hosts`, which also applies to
  `tokenUrl` in US-2.2.
- `EndpointClientRegistry` holds a `ConcurrentHashMap<name, EndpointRuntime>`. Each
  `EndpointRuntime` holds:
  - a `RestClient` built from `restClientBuilder.clone()`, so interceptors never leak between
    endpoints;
  - a JDK `HttpClient` with the connect timeout, HTTP version and the shared `apiorch-http`
    executor;
  - request factories per timeout bucket (US-5.1);
  - the Resilience4j instances (US-5.2 to 5.4).
- Runtimes are rebuilt on `EndpointChangedEvent` (`@TransactionalEventListener(AFTER_COMMIT)`),
  and by a scheduled refresh that compares `row_version`s, so changes reach other nodes.
  JDK 17's `HttpClient` has **no** `close()`, so a replaced runtime is simply dereferenced, and
  its connections are dropped when idle.
- `EndpointReferenceChecker` finds references in the latest version and draft of each pipeline,
  plus every version in `SELECT DISTINCT pipeline_code, pipeline_version FROM pipeline_run WHERE
  ended_at IS NULL`.
- `UriBuilder` builds URIs with `UriComponentsBuilder.fromUriString(baseUrl).pathSegment(...)`,
  never by string concatenation. The result must keep scheme, host and port, and start with
  every base path segment (compared segment by segment).

**Configuration:** `apiorch.endpoints.allow-http` (`false`, `true` in `local`),
`apiorch.endpoints.allowed-hosts` (`[]`; `local` defaults to `localhost`) and
`apiorch.endpoints.refresh-interval-ms` (`10000`).

**Logging:** create, update and delete are logged at INFO with the name, version, actor and the
names of the fields that changed, but no `auth` values. Rebuilds are logged at INFO. The full
history is US-2.3.

**Test notes**
- `EndpointApiIT` covers CRUD, ETag and If-Match (409/428), validation including ranges, host
  allow-list and `healthPath`, and the already-exists case (AC1–AC3, AC7).
- `EndpointHotReloadIT` uses two contexts on one shared database (the same named H2
  in-memory database in the fast tier). It updates the base
  URL via node A, and asserts that node B's next attempt goes to the new WireMock port within
  the refresh interval (AC4).
- `EndpointDeleteIT` covers latest, draft and a non-terminal run on an older version (AC5).
- `EndpointProbeIT` covers 200, 503, a delay beyond the timeout, and a `healthPath`
  injection attempt (AC2, AC6).
- `RestClientIsolationTest` builds two endpoints with different auth and asserts that each
  request carries only its own interceptors.
