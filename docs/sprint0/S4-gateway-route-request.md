# S4: Platform gateway route for apiorch (draft PR description)

**Target repository:** `parasdshah/workflow-user-stories`
**File:** `api-gateway/src/main/resources/application.yml`
**Story:** US-1.4 (R1 exit requires "reachable through the platform gateway")
**Status:** draft, not yet raised. Raising it is an external PR and needs the platform team.

## Change

Add one route **between the `hrms-service` route and the `workflow-service` `/api/**`
catch-all**. Every route has the default order, so list position decides. A route placed after the
catch-all is never reached, and its requests 404 at workflow-service.

```yaml
        - id: apiorch-service
          uri: lb://apiorch-service
          predicates:
            - Path=/api/apiorch/**
          filters:
            - RewritePath=/api/apiorch/(?<segment>.*), /api/$\{segment}
```

The same fragment is committed in this repository at
[`ops/gateway/apiorch-route.yml`](../../ops/gateway/apiorch-route.yml).

## Why

- apiorch registers with the platform Eureka (`service-registry`, port 9100) as
  `apiorch-service`. That is its `spring.application.name`, and it carries the product-code prefix.
- Its public paths are `/api/apiorch/**`, so they can never collide with workflow-service's
  `/api/*`. The rewrite maps them to the service's own `/api/v1/...` paths.
- Async downstreams call back through the gateway (`apiorch.callback.public-base-url` includes
  `/api/apiorch`), so the route must exist before any R2 async pipeline goes live.

## Not requested

- **CORS:** apiorch has no browser client in this backlog, so the `globalcors` allow-list is
  unchanged.
- No second gateway or Eureka server. apiorch only runs `eureka-client`.

## Verification after merge

1. `GET http://localhost:9100/eureka/apps` with `Accept: application/json` lists `APIORCH-SERVICE`.
2. `GET http://localhost:9110/api/apiorch/v1/...` reaches apiorch, not workflow-service.
3. `GET http://localhost:9110/api/hrms/...` and other `/api/**` paths still reach their old
   services. This is a regression check on the route order.

Until it merges, the discovery route `http://localhost:9110/apiorch-service/api/v1/...` works
with no gateway change and is enough for development.
