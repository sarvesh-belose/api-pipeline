# US-1.4 Publish apiorch on a stable public address
**Epic:** E1 Foundation · **Priority:** Must · **Size:** S · **Depends on:** US-1.1 · **Release:** R1

## User story
As an **Operations Engineer**, I want apiorch reachable on one stable public address in front of
all its nodes, so that calling systems and async downstream APIs have an address that survives
restarts and scaling, instead of talking to a node directly.

## Description
apiorch is an independent product. It does not use a shared API gateway or service registry. Each
environment puts the apiorch nodes behind **its own load balancer or ingress** (owned by the team
running the environment), and that address is the only one given to callers and downstream
systems.

Three things are needed:
1. **One public base URL per environment**, for example `https://apiorch.<env>.example.internal`.
   The public paths are the service's own paths (`/api/v1/**`, README §9). There is no path prefix
   and no rewrite.
2. **Correct links behind the load balancer.** apiorch returns absolute links: the `Location` header
   on `202` (US-4.1, US-4.2) and the callback URL handed to downstream systems (US-6.1). Behind a
   load balancer they must carry the public scheme and host, not the node's.
3. **Callback URLs must be the public address.** `apiorch.callback.public-base-url` is handed to
   external downstream systems (US-6.1) and must resolve from outside. A node address there makes
   every async callback fail after a restart, and silently: the step simply times out (US-6.4) with
   nothing pointing at the cause.

The load balancer needs no session affinity: any node can serve any request, and a callback is
handled on the node that receives it (US-6.2).

**Out of scope:** provisioning the load balancer, TLS certificates and DNS. Those belong to the
environment's infrastructure. Edge rate limiting is not assumed, which is why US-6.2's callback
rate limit stays in the application and is recorded in the runbook (US-8.4).

## Acceptance criteria
- [ ] **AC1:** Given two apiorch nodes behind the load balancer, when
  `POST {public-base-url}/api/v1/pipelines/{code}/runs?mode=sync` is called, then the response,
  status code and `X-Apiorch-*` headers are identical to calling a node directly.
- [ ] **AC2:** Given a request arriving through the load balancer with `X-Forwarded-Proto: https` and
  `X-Forwarded-Host: apiorch.test.example.internal`, when a run answers `202`, then `Location` is
  `https://apiorch.test.example.internal/api/v1/runs/{runId}`. Forwarded headers are trusted only
  from the addresses in `server.tomcat.remoteip.internal-proxies`; from any other source they are
  ignored and the node's own address is used.
- [ ] **AC3:** Given `POST {public-base-url}/api/v1/callbacks/{runId}/{stepId}?t=<token>`, then it
  reaches the callback controller with the `t` parameter and any `X-Callback-Token` header intact,
  and the body is not altered. A callback is the one unauthenticated path in the system (US-1.2
  chain 1), so it must pass through the load balancer unchanged.
- [ ] **AC4:** Given `apiorch.callback.public-base-url` is set to a node address (an IP literal,
  `localhost`, or a host that does not match `apiorch.callback.expected-public-host`), when the app
  starts outside the `local` profile, then **startup fails** with a message naming the expected
  public host. This extends US-6.1 AC7 from "is it https" to "is it actually reachable from
  outside".
- [ ] **AC5:** Given the management port (9091), then it is **not** published through the load
  balancer: `/actuator/**` is reachable only on the management port, and
  `{public-base-url}/actuator/health` returns `404` problem+json from the API port. Health checks
  probe each node's management port directly, as US-1.1 AC2 already does.
- [ ] **AC6:** Given a node shutting down gracefully (US-5.5), when readiness turns `OUT_OF_SERVICE`,
  then the load balancer's health check (readiness on 9091) removes the node before in-flight
  requests finish, and no new request reaches it.
- [ ] **AC7:** Given the deployment guide, then it records per environment: the public base URL,
  the load balancer's health-check path and interval, the trusted proxy addresses, and the value of
  `apiorch.callback.expected-public-host`.

## Technical details
**Configuration**
```yaml
server:
  forward-headers-strategy: native        # Tomcat RemoteIpValve: X-Forwarded-Proto/Host/For
  tomcat.remoteip.internal-proxies: ${APIORCH_TRUSTED_PROXIES:10\.\d{1,3}\.\d{1,3}\.\d{1,3}}
apiorch.callback.public-base-url: ${APIORCH_PUBLIC_BASE_URL}     # e.g. https://apiorch.test.example.internal
apiorch.callback.expected-public-host: ${APIORCH_PUBLIC_HOST:}   # guard for AC4
```
`local` and `test` leave `forward-headers-strategy` at `native` with the default trusted proxies, so
direct calls behave exactly as before.

**Components:** `config.PublicBaseUrlValidator` is an `ApplicationRunner` guard implementing AC4,
alongside the existing US-6.1 scheme check. No apiorch code depends on a load balancer being
present: the service is fully functional when called directly, which keeps the tests independent
of any infrastructure.

**Dependencies:** none beyond US-1.1. No service-registry client, no gateway.

**CORS:** apiorch has no browser client in this backlog (US-7.4 is JSON for third-party tools), so
no CORS configuration is added.

**Test notes**
- `ForwardedHeadersIT` sends `X-Forwarded-Proto`/`X-Forwarded-Host` from a trusted and an untrusted
  address and asserts the `Location` header for both (AC2).
- `PublicBaseUrlGuardTest` covers AC4 for an IP literal, `localhost`, a mismatched host and a valid
  public URL, in the `prod` and `local` profiles.
- `ManagementPortIsolationIT` asserts `/actuator/health` is `404` on the API port and `UP` on the
  management port (AC5).
- A manual checklist in the deployment guide covers AC1, AC3 and AC6 against a real load balancer,
  since those assert the environment's behaviour rather than apiorch's.
