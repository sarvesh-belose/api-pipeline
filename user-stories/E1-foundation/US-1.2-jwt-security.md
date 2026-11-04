# US-1.2 Secure APIs with SSO JWT and roles
**Epic:** E1 Foundation · **Priority:** Must · **Size:** M · **Depends on:** US-1.1 · **Release:** R1

## User story
As a **Security Officer**, I want every apiorch business API protected by JWTs from our
existing SSO, with role-based access for callers, administrators and operators, while health
probes and signed callbacks keep working without a JWT, so that only authorised systems can
start runs, change pipelines or see payloads, and the service stays operable.

## Description
apiorch runs as an **OAuth2 resource server**. It validates bearer JWTs from the organisation's
identity provider and maps a roles claim to three roles: `apiorch.run`, `apiorch.admin` and
`apiorch.ops` (README §12). No user or role tables are created.

Security is split into **separate, ordered filter chains**, so each class of traffic gets
exactly the filters it needs:
1. **Callbacks:** no resource server. Callers are authenticated by the signed token (US-6.2).
2. **Actuator** on the management port: probes are open; Prometheus is restricted to the
   internal network.
3. **API docs**, in `local` only.
4. **Business API.**

A `dev` security mode for local work grants all roles and is allowed **only** when the `local`
profile is active.

**Out of scope:** issuing tokens, user management, and per-pipeline caller allow-lists (US-4.8).

## Acceptance criteria
- [ ] **AC1:** Given a request without a bearer token to `/api/v1/**` (except
  `/api/v1/callbacks/**`), when processed, then the response is `401` `unauthorized` with
  `WWW-Authenticate: Bearer`.
- [ ] **AC2:** Given a JWT that is expired, signed by an unknown key, or has the wrong issuer or
  audience, when presented, then the response is `401`, and the reason is logged at WARN without
  the token value.
- [ ] **AC3:** Given each endpoint in README §9 and each role, when called, then access matches
  the matrix. For example, `apiorch.run` gets `403` `forbidden` on `/api/v1/admin/**`, and
  `apiorch.ops` gets `200` on `GET /api/v1/admin/pipelines` but `403` on
  `POST …/publish`. This is verified by a parameterised test over every controller mapping.
- [ ] **AC4:** Given a callback request to `/api/v1/callbacks/**` carrying an unrelated
  `Authorization: Bearer xyz` header, when processed, then the JWT filter does **not** reject it,
  and the request reaches the callback controller.
- [ ] **AC5:** Given the management port, when Kubernetes calls
  `/actuator/health/liveness` and `/actuator/health/readiness` without credentials, then they
  return `200`. `/actuator/prometheus` is allowed only from the CIDR ranges in
  `apiorch.security.metrics-allowed-cidrs`; without credentials from other addresses Prometheus
  is refused (401 or 403). Any other actuator endpoint is refused (401 or 403). The test does not
  pin the code: Spring Security 6.5 answers an unauthenticated `denyAll()` with `401`
  (ADR-003 F12).
- [ ] **AC6:** Given `apiorch.security.roles-claim=realm_access.roles`, when a token carries
  roles in that nested claim, then they are mapped. The principal recorded in audit columns
  comes from `apiorch.security.principal-claim` (default `sub`).
- [ ] **AC7:** Given `apiorch.security.mode=dev`, when the app starts **without** the `local`
  profile active (for example with `uat`, `prod` or no profile), then startup fails with a clear
  message. With `local`, every request is `dev-user` with all three roles, and a WARN banner
  `DEV SECURITY MODE – NOT FOR PRODUCTION` is logged.
- [ ] **AC8:** Given an unknown path under `/api/v1/**` with a valid JWT, when called, then the
  response is `404` `not-found`, not 403.

## Technical details
**Filter chains** (`com.crisil.apiorch.security.SecurityConfig`, Spring Security 6.5 lambda DSL
only; `EndpointRequest` is from `org.springframework.boot.actuate.autoconfigure.security.servlet`
on Boot 3.5, ADR-003):

| Order | `securityMatcher` | Rules |
|---|---|---|
| 1 | `/api/v1/callbacks/**` | `permitAll`, **no** `oauth2ResourceServer`, stateless, CSRF off |
| 2 | `EndpointRequest.toAnyEndpoint()` (management context) | `health`, `health/**` → `permitAll`; `prometheus` → `CidrAuthorizationManager` (`apiorch.security.metrics-allowed-cidrs`, default `10.0.0.0/8,127.0.0.1/32`); else `denyAll` |
| 3 | `/swagger-ui/**`, `/v3/api-docs/**` (only `@Profile("local")`) | `permitAll` |
| 4 | `/**` (business API) | Rules below, then `/api/**` `authenticated()` so unknown routes reach MVC and return 404, plus `/error` `permitAll`, then `anyRequest().denyAll()` |

**Rules in chain 4** (first match wins, most specific first):
1. `POST /api/v1/pipelines/*/runs` → `hasRole('apiorch.run')`. The `version` override is
   checked in the service (US-4.1).
2. `GET /api/v1/runs/*/steps/*/attempts/*`, `GET /api/v1/runs/*/steps/*/callbacks/*` and
   `GET /api/v1/runs/*/exchanges/*` → `hasRole('apiorch.ops')`.
3. `POST /api/v1/runs/*/cancel`, `/resume`, `/steps/*/resolve`, `/webhooks/*/redeliver`, and
   `POST /api/v1/runs/erase` → `hasRole('apiorch.ops')`.
4. `GET /api/v1/runs/**` (including the search endpoint `/api/v1/runs`) →
   `hasAnyRole('apiorch.run','apiorch.ops')`. Ownership is checked in the service (US-4.2).
5. `/api/v1/admin/endpoints/*/circuit/**` → `hasRole('apiorch.ops')`.
6. `POST /api/v1/admin/pipelines/*/disable`, `/enable` →
   `hasAnyRole('apiorch.admin','apiorch.ops')`.
7. `GET /api/v1/admin/**` → `hasAnyRole('apiorch.admin','apiorch.ops')`.
8. `/api/v1/admin/**` → `hasRole('apiorch.admin')`.

**Components**
- `RolesClaimConverter` implements `Converter<Jwt, AbstractAuthenticationToken>`. It reads a
  dotted claim path and maps each value to authority `ROLE_<value>`.
- `CurrentPrincipal.name()` returns the principal claim, used for `created_by`, `published_by`
  and `actor`.
- `DevSecurityConfig` is `@ConditionalOnProperty(apiorch.security.mode=dev)`. Its guard bean
  throws unless `environment.acceptsProfiles(Profiles.of("local"))`.
- A custom `AuthenticationEntryPoint` and `AccessDeniedHandler` write problem+json with
  `correlationId`.

**Configuration**
```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${APIORCH_JWT_ISSUER_URI}      # JWKS discovered from the issuer; set jwk-set-uri only if discovery is unavailable
  audiences: ${APIORCH_JWT_AUDIENCE:apiorch}
apiorch.security: { mode: jwt, roles-claim: roles, principal-claim: sub, metrics-allowed-cidrs: "10.0.0.0/8,127.0.0.1/32" }
```
Don't define `jwk-set-uri` with an empty default. An empty value still activates the
property-based decoder and breaks startup.

**Logging:** auth failures are logged at WARN with the path, the reason and the client IP. The
token is never logged.

**Test notes**
- `SecurityMatrixIT` uses `@ParameterizedTest` over (endpoint, role, expected status) with
  `jwt().authorities(...)` (AC3, AC8).
- `JwtValidationIT` runs a WireMock JWKS with a Nimbus-signed token: valid, expired, wrong `iss`,
  wrong `aud` (AC2).
- `CallbackChainIT` sends a bearer header to a callback (AC4).
- `ActuatorSecurityIT` covers the probes, Prometheus with allowed and denied addresses, and other
  endpoints (AC5); refusals assert "401 or 403" (ADR-003 F12).
- `DevModeGuardTest` covers `uat`, `prod`, no profile and `local` (AC7).
