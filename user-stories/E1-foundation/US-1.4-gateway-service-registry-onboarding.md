# US-1.4 Reach apiorch through the platform gateway and service registry
**Epic:** E1 Foundation · **Priority:** Must · **Size:** S · **Depends on:** US-1.1 · **Release:** R1

## User story
As an **Operations Engineer**, I want apiorch registered with the platform service registry and
reachable on a stable public path through the platform API gateway, so that calling systems and
async downstream APIs have one address that survives pod restarts and scaling, instead of talking
to a pod directly.

## Description
apiorch does not publish its own edge. The platform owns it: a **Eureka service registry** (port
9100) and a **Spring Cloud Gateway** (port 9110). Every other story in this backlog quietly assumed
direct pod exposure, which is not how this deploys and which breaks async callbacks outright.

Three things are needed:
1. **Register with Eureka.** `spring.application.name: apiorch-service` (the service id and the
   `lb://` host) plus the `eureka-client` starter pointed at the registry.
2. **A public path prefix.** The gateway's last route is a catch-all `Path=/api/**` to
   workflow-service, and **list position decides** because every route takes the default order. A
   route added after it is never reached, and a product route under a bare `/api/<resource>` path
   collides with workflow-service. apiorch is therefore published under **`/api/apiorch/**`**, with
   the prefix stripped by `RewritePath`, so the service keeps the `/api/v1/**` paths in README §9
   unchanged and only the public address gains a prefix.
3. **Callback URLs must be the gateway's address.** `apiorch.callback.public-base-url` is handed to
   external downstream systems (US-6.1) and must resolve from outside the cluster. A pod address
   there means every async callback fails after a restart, and silently: the step simply times out
   (US-6.4) with nothing pointing at the cause.

The gateway change edits the platform repository, so it is **proposed as a pull request** to
`api-gateway/src/main/resources/application.yml` (or through `/platform-core:capability-request`),
not made locally. apiorch never adds a gateway or a registry server of its own.

**Out of scope:** TLS, authentication and rate limiting at the edge. The platform gateway has none
today, which is a platform production-readiness item, not an apiorch one — but it is why US-6.2's
callback rate limit stays in the application and is recorded in the runbook (US-8.4).

## Acceptance criteria
- [ ] **AC1:** Given the registry at `http://localhost:9100`, when apiorch starts, then it appears
  at `GET /eureka/apps` (with `Accept: application/json`) as `APIORCH-SERVICE` with its instance id
  and status `UP`. When it shuts down gracefully (US-5.5), it deregisters, so the gateway stops
  routing to it before the pod goes away.
- [ ] **AC2:** Given the proposed gateway route, when
  `POST http://localhost:9110/api/apiorch/v1/pipelines/{code}/runs?mode=sync` is called, then it
  reaches apiorch's `RunController` as `/api/v1/pipelines/{code}/runs`, and the response, status
  code and `X-Apiorch-*` headers are identical to calling the pod directly. The route is placed
  **between** `hrms-service` and the `/api/**` catch-all, which is verified by its list position in
  the proposed YAML.
- [ ] **AC3:** Given `POST http://localhost:9110/api/apiorch/v1/callbacks/{runId}/{stepId}?t=<token>`,
  then it reaches the callback controller with the `t` parameter and any `X-Callback-Token` header
  intact, and the body is not altered. A callback is the one unauthenticated path in the system
  (US-1.2 chain 1), so it must traverse the gateway unchanged.
- [ ] **AC4:** Given `apiorch.callback.public-base-url` is set to a pod or container address
  (anything whose host is an IP literal, `localhost`, or does not match
  `apiorch.callback.expected-public-host`), when the app starts outside the `local` profile, then
  **startup fails** with a message naming the gateway address to use. This extends US-6.1 AC7 from
  "is it https" to "is it actually reachable from outside".
- [ ] **AC5:** Given the readiness probe on the management port (9091), then it is **not** exposed
  through the gateway: `/actuator/**` is reachable only on the management port, and a request to
  `http://localhost:9110/api/apiorch/actuator/health` does not return actuator data. Kubernetes
  probes the pod directly, as US-1.1 AC2 already does.
- [ ] **AC6:** Given the service is registered, then it is also reachable on the discovery route
  `http://localhost:9110/apiorch-service/api/v1/...` without any gateway change. This is documented
  as the **development and service-to-service** path only; the explicit route in AC2 is the one
  callers and downstream systems are given, so no public address carries an internal service id.
- [ ] **AC7:** Given the deployment guide, then it records the gateway base URL per environment, the
  exact route YAML proposed to the platform, and the fact that a route added after `/api/**` is
  silently unreachable. An engineer onboarding the next environment should not have to rediscover
  the ordering rule.

## Technical details
**Dependencies**
- `org.springframework.cloud:spring-cloud-starter-netflix-eureka-client`, with the version from the
  Spring Cloud BOM matching the chosen Boot line.
- **Pairing: resolved by [ADR-003](../../docs/adr/ADR-003-boot35-sqlserver.md).** Spring Cloud
  **2025.0.3** with Boot **3.5.14**: its compatibility verifier, which is on by default, passed at
  startup in the probe. The resolved artifacts are Spring Cloud commons and
  `spring-cloud-netflix-eureka-client` 4.3.3, and Netflix `eureka-client` 2.0.6.
- **Support caveat (ADR-003 F1):** Boot 3.5's open-source support ended 2026-06-30, and Spring
  Cloud 2025.0.3 itself targets Boot 3.5.15. The owner accepted this; CVE patches after 3.5.14
  need a commercial subscription or a patch bump within 3.5.x.
- **Jackson:** Netflix `eureka-client` uses Jackson 2, which is the only Jackson on this line.
  Product code must not import Jackson 3 (`tools.jackson`) (US-1.1 AC11, ADR-003 F7).

**Configuration (apiorch)**
```yaml
spring.application.name: apiorch-service
eureka:
  client:
    service-url.defaultZone: ${APIORCH_EUREKA_URL:http://localhost:9100/eureka/}
    enabled: ${APIORCH_EUREKA_ENABLED:true}      # false in the local and test profiles
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
apiorch.callback.public-base-url: ${APIORCH_PUBLIC_BASE_URL}     # the gateway, incl. /api/apiorch
apiorch.callback.expected-public-host: ${APIORCH_PUBLIC_HOST:}   # guard for AC4
```
Eureka is **disabled** in `local` and `test`, so neither the demo (US-8.1) nor the test suite needs
a registry running.

**Proposed platform change** (`api-gateway/src/main/resources/application.yml`), between the
`hrms-service` route and the `/api/**` catch-all:
```yaml
      - id: apiorch-service
        uri: lb://apiorch-service
        predicates:
          - Path=/api/apiorch/**
        filters:
          - RewritePath=/api/apiorch/(?<segment>.*), /api/$\{segment}
```

**Components:** `config.PublicBaseUrlValidator` is an `ApplicationRunner` guard implementing AC4,
alongside the existing US-6.1 scheme check. No apiorch code depends on the gateway being present —
the service is fully functional when called directly, which is what keeps the tests
registry-independent.

**CORS:** the gateway allows only the workflow UI origin today. apiorch has no browser client in
this backlog (US-7.4 is JSON for third-party tools), so no CORS change is requested. If a UI is
added later, its origin goes in the same platform change as its route.

**Tenancy:** every platform-side object this product owns is prefixed with the product code —
the service id `apiorch-service` and the route id `apiorch-service` both carry it.

**Test notes**
- `EurekaRegistrationIT` runs a stub registry and asserts the registration payload and graceful
  deregistration (AC1). Tagged `slow`.
- `GatewayRouteContractTest` parses the proposed YAML fragment committed under
  `ops/gateway/apiorch-route.yml` and asserts the path predicate, the rewrite, and that it appears
  before any `Path=/api/**` entry (AC2). This is a cheap guard against the ordering mistake and
  needs no running gateway.
- `PublicBaseUrlGuardTest` covers AC4 for an IP literal, `localhost`, a mismatched host and a valid
  gateway URL, in the `prod` and `local` profiles.
- A manual checklist in the deployment guide covers AC3, AC5 and AC6 against a running platform
  stack, since those assert the platform's behaviour rather than apiorch's.
