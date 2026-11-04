# US-2.2 Authenticate to downstream APIs via secret references
**Epic:** E2 Downstream endpoints · **Priority:** Must · **Size:** M · **Depends on:** US-2.1 · **Release:** R1

## User story
As a **Security Officer**, I want each endpoint's credentials stored only as references to
environment variables or mounted secret files, and applied automatically on every call, so that
no secret is ever saved in the database, returned by an API or written to a log.

## Description
Each endpoint declares one auth type:

| Type | Applied as |
|---|---|
| `NONE` | nothing |
| `API_KEY` | header `<headerName>: <secret>` |
| `BEARER` | `Authorization: Bearer <secret>` |
| `BASIC` | `Authorization: Basic base64(username:password)` |
| `OAUTH2_CLIENT_CREDENTIALS` | `Authorization: Bearer <access_token>`, fetched from the token URL and cached |

Secrets are written as **references**, `env:NAME` or `file:/path/to/secret`. A pluggable
`SecretResolver` makes it possible to add Vault later without changing pipelines or endpoints.

References are **confined**, so an admin can't make apiorch send its own infrastructure secrets
to a host they control:
- `env:` names must start with `apiorch.secrets.env-prefix` (`APIORCH_SECRET_`).
- `file:` paths must resolve, after canonicalization and symlink resolution, inside
  `apiorch.secrets.file-dir` (`/run/secrets`).
- Token URLs must match `apiorch.endpoints.allowed-hosts`.
- Changing an endpoint's `baseUrl` host or `tokenUrl` requires the secret references to be
  submitted again in the same request.

**Out of scope:** mTLS client certificates, Vault and AWS Secrets Manager resolvers (left as
extension points), and inbound auth (US-1.2).

## Acceptance criteria
- [ ] **AC1:** Given an endpoint saved with `"auth": {"type":"API_KEY","headerName":"X-Api-Key",
  "secretRef":"env:APIORCH_SECRET_KYC_API_KEY"}` and the environment variable set, when a step
  calls it, then the request carries `X-Api-Key: <value>`, and the stored attempt and logs show
  `X-Api-Key: ***`.
- [ ] **AC1b (confinement):** Given `secretRef` values `env:SPRING_DATASOURCE_PASSWORD`,
  `file:/proc/self/environ` or `file:/run/secrets/../etc/passwd`, or a `tokenUrl` host outside
  `apiorch.endpoints.allowed-hosts`, when saved, then each is rejected with `400`
  `validation-failed`. Given a `PUT` that changes the `baseUrl` host without re-supplying the
  secret references, it is rejected with `400` ("re-enter secret references when the host
  changes").
- [ ] **AC2:** Given an endpoint body containing a literal secret, for example
  `"secretRef":"abc123"` or any field named `secret`, `password` or `clientSecret` with a value
  not matching `^(env|file):.+$`, when it is saved, then it is rejected with `400`
  `validation-failed` ("secrets must be references").
- [ ] **AC3:** Given `OAUTH2_CLIENT_CREDENTIALS` with `tokenUrl`, `clientId`, `clientSecretRef`
  and `scope`, when several steps call the endpoint, then a token is fetched once and reused until
  `expires_in - 60 s`. Concurrent calls trigger a single token request (single flight).
- [ ] **AC4:** Given a cached OAuth2 token that the downstream rejects with `401`, when the step
  call returns, then apiorch evicts the token, fetches a new one, and repeats the call **once**.
  The repeat is part of the **same attempt**: it doesn't count against `retry.maxAttempts`,
  exactly one `step_attempt` row is written (the final exchange), and the event `TOKEN_REFRESHED`
  `{endpoint}` is recorded. A second `401` fails the attempt with outcome `AUTH_ERROR`.
- [ ] **AC5:** Given a reference that can't be resolved (missing environment variable or
  unreadable file) or a token endpoint that is down, when a step calls the endpoint, then the
  attempt fails with outcome `AUTH_ERROR` and error code `SECRET_UNAVAILABLE` or
  `TOKEN_FETCH_FAILED`. No HTTP request is sent to the downstream API. The failure isn't retried
  by step retry, and the step's `onFailure` policy applies.
- [ ] **AC6:** Given a secret file that is rotated on disk, when
  `apiorch.secrets.cache-ttl-ms` (5 min) has passed, then the new value is used without a
  restart.
- [ ] **AC7:** Given `GET /api/v1/admin/endpoints/{name}`, when called, then the `auth` object
  shows the type, non-secret fields (`headerName`, `username`, `tokenUrl`, `clientId`, `scope`)
  and references only. Resolved values never appear in responses, logs, `run_event` or
  `step_attempt`.

## Technical details
**Auth JSON per type** (stored in `endpoint.auth_config`)
```json
{ "type": "API_KEY", "headerName": "X-Api-Key", "secretRef": "env:KYC_API_KEY" }
{ "type": "BEARER", "secretRef": "file:/run/secrets/scoring-token" }
{ "type": "BASIC", "username": "apiorch", "passwordRef": "env:LEGACY_PWD" }
{ "type": "OAUTH2_CLIENT_CREDENTIALS", "tokenUrl": "https://idp.example.com/oauth2/token",
  "clientId": "apiorch", "clientSecretRef": "env:CORE_CLIENT_SECRET",
  "scope": "accounts.write", "audience": "core-banking" }
```

**Components** (`com.crisil.apiorch.endpoint` and `com.crisil.apiorch.http`)
- `SecretResolver` is an interface: `Optional<String> resolve(String ref)`.
  - `EnvSecretResolver` handles `env:` and `FileSecretResolver` handles `file:` (UTF-8, trimmed).
  - `CompositeSecretResolver` picks a resolver by scheme and caches values in Caffeine for
    `apiorch.secrets.cache-ttl-ms`.
  - An unknown scheme is a validation error at save time.
- `AuthApplier` is a strategy per type, applied as a `ClientHttpRequestInterceptor` on the
  endpoint's `RestClient`: `NoAuthApplier`, `ApiKeyAuthApplier`, `BearerAuthApplier`,
  `BasicAuthApplier`, `OAuth2ClientCredentialsApplier`.
- `OAuth2ClientCredentialsApplier` uses `OAuth2TokenCache`, keyed by endpoint name and the
  auth-config hash. It fetches tokens via a plain `RestClient` POST
  (`grant_type=client_credentials`, client auth `client_secret_basic`) with single-flight
  `CompletableFuture` per key. A 401 from the downstream API raises `TokenRejectedException`,
  which the applier handles *inside* the interceptor chain by refreshing once and repeating once
  (AC4). The capture interceptor (US-7.2) sits outside the auth interceptor, so it records only
  the final exchange.
- `SecretReferenceValidator` is a Bean Validation constraint on `*Ref` fields. It enforces the
  scheme, the env prefix and the canonical file directory (AC1b). It also rejects unknown fields
  named `secret`, `password` or `clientSecret` (AC2).
- **AC2 depends on strict binding** (US-1.1 AC12). Boot's auto-configured `ObjectMapper`
  disables `FAIL_ON_UNKNOWN_PROPERTIES` (ADR-003 F7), so without it a literal secret in an unrecognised field such as
  `"password": "hunter2"` would be **silently dropped**, not rejected. The admin would believe it
  was stored, and the call would then fail at runtime. `EndpointAuthApiIT` includes that exact
  body and expects `400`.
- `EndpointService` rejects host changes that don't re-supply the secret references (AC1b).

**Error codes on step failure:** `SECRET_UNAVAILABLE`, `TOKEN_FETCH_FAILED`, `TOKEN_REJECTED`.
Each records attempt outcome `AUTH_ERROR`. The token request itself is not recorded as a
`step_attempt`; it is logged at DEBUG with endpoint and status only.

**Masking:** the headers named in `apiorch.logging.always-mask-headers` (README §11), plus any
`headerName` configured for `API_KEY`, are masked in every log and capture (enforced by US-7.3).

**Configuration:** `apiorch.secrets.cache-ttl-ms` (`300000`), `apiorch.secrets.env-prefix`
(`APIORCH_SECRET_`), `apiorch.secrets.file-dir` (`/run/secrets`), and
`apiorch.endpoints.allowed-hosts`, which also applies to `tokenUrl`.

**Test notes**
- `AuthAppliersTest` checks the header produced per type.
- `SecretResolverTest` covers env, file, missing and unknown scheme.
- `OAuth2ClientCredentialsIT` uses a WireMock token endpoint and a WireMock downstream:
  - token reuse;
  - single flight under 20 concurrent calls;
  - 401 → refresh → success;
  - 401 twice → `AUTH_ERROR`;
  - token endpoint 500 → `TOKEN_FETCH_FAILED` (AC3–AC5).
- `SecretRotationTest` uses a temp file, rewrites it, and advances a Caffeine fake ticker (AC6).
- `EndpointAuthApiIT` checks literal-secret rejection and that responses contain no values
  (AC2, AC7).
- `SecretConfinementTest` covers the env prefix, file traversal and symlinks, the token-URL host,
  and host changes without re-entered references (AC1b).
- `TokenRefreshSingleAttemptIT` checks one `step_attempt` row plus one `TOKEN_REFRESHED` event
  (AC4).
