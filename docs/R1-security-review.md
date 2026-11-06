# R1 security review pack

The R1 security review is the end-of-Sprint 6 checkpoint in the
[implementation plan](R1-implementation-plan.md) §8. Its scope is the security chains, how secrets are
confined, masking, encryption, and the append-only grants. This pack gathers the design, the code and the
automated evidence for each area, and lists the open points the reviewer must decide on.

**Status (2026-11-06):** prepared for review. The review itself has not been held; record its outcome and any
findings at the end of this file.

Every test named here runs in `./mvnw verify` (H2) or `./mvnw verify -Pslow` (SQL Server 2022), and both tiers
are green on the Sprint 6 branch.

## 1. Inbound authentication and authorization (US-1.2)

| Control | Where | Evidence |
|---|---|---|
| OAuth2 resource server: JWT signature, issuer, audience and expiry checked; roles from the `roles` claim | `security.SecurityConfig`, `RolesClaimConverter` | `JwtValidationIT` |
| Role matrix: `apiorch.run` starts runs and reads its own; `apiorch.ops` reads all, sees payloads and acts on runs; `apiorch.admin` changes definitions and endpoints | `SecurityConfig.apiChain` | `SecurityMatrixIT` (32 path × role cases) |
| A run's creator is the only caller who can read it; anything else is `404`, so run ids can't be probed | `RunQueryService`, `TimelineService` | `AsyncRunIT`, `TimelineIT` |
| Payload detail (attempts, exchanges) needs `apiorch.ops`; every successful read records `PAYLOAD_ACCESSED` with the actor; a `403` is counted in `apiorch.payload.access.denied` | `TimelineService.recordAccess`, `ProblemSecurityHandlers` | `TimelineIT` |
| Operator actions (cancel, resolve, circuit reset) need `apiorch.ops` and a reason, recorded with the actor | `RunOperatorService`, `CircuitController` | `CancelIT`, `ResolveUnknownIT`, `CircuitBreakerIT` |
| Dev mode (every request is `dev-user`) refuses to start outside the `local` profile | `SecurityConfig.requireLocalForDevMode` | `LocalDevModeIT`, `SecurityUnitTest` |
| Actuator: health is open, Prometheus is limited by CIDR, everything else is on the management port only | `SecurityConfig`, `CidrAuthorizationManager` | `CallbackAndActuatorSecurityIT`, `PrometheusCidrIT`, `ApplicationSmokeIT` |
| OpenAPI and Swagger UI exist only in `local`, and are disabled in `prod` | `SecurityConfig` (`@Profile("local")`), `application-prod.yml` | configuration review |
| 401 and 403 are problem+json; the token is never logged | `ProblemSecurityHandlers` | `SecurityMatrixIT` |

## 2. Secret confinement (US-2.2, US-7.6)

| Control | Where | Evidence |
|---|---|---|
| Downstream secrets are stored only as references (`env:APIORCH_SECRET_*`, `file:` under `apiorch.secrets.file-dir`); a literal secret in a request is refused | `SecretReferencePolicy`, `EndpointValidator` | `SecretReferencePolicyTest`, `EndpointAuthApiIT` |
| Secret values are never returned by the API or written to logs | `EndpointMapper`, `AuthInterceptor` | `EndpointAuthApiIT` |
| OAuth2 tokens are cached in memory only, refreshed once on `401` | `OAuth2TokenCache` | `AuthInterceptorIT`, `TokenRefreshSingleAttemptIT` |
| Context encryption keys come from `env:` or `file:` only; outside `local` the app refuses to start without one | `ContextCipherConfig` | `ContextCipherConfigTest` |
| The test key is test-only, under `src/test/resources/test-secrets`; production reads `APIORCH_SECRET_CTX_K1` | `application-test.yml`, `application-prod.yml` | configuration review |

## 3. Masking (US-7.3, US-7.7)

| Control | Where | Evidence |
|---|---|---|
| Credential headers are always masked; bodies are masked by key name or path before truncation; URL query values are masked | `MaskingPolicy`, `PayloadMasker`, `AttemptWriter` | `PayloadMaskerTest`, `AttemptCaptureIT` |
| The caller's request and every response are stored masked, with the pipeline's capture level | `RunExchangeWriter` | `InboundExchangeIT`, `CallerResponseIT`, `ExchangeApiIT` |
| The log layouts mask the same keys and known value shapes as a safety net | `MaskingMessageConverter`, `logback-spring.xml` | `LogMessageMaskingTest` |
| A planted PAN-like value appears in no log line, no attempt row and no event | — | `MaskingEndToEndIT` (canary) |
| The default capture level is `METADATA`: no bodies unless a pipeline asks for them | `ApiorchProperties.Logging` | `InboundExchangeIT` |

## 4. Encryption of working data (US-7.6)

| Control | Where | Evidence |
|---|---|---|
| `run_context` is AES-256-GCM with a per-run key (HKDF-SHA256) and AAD `runId|entryKey`, stored `v1:<kid>:…` | `ContextCipher`, `RunRows` | `ContextCipherTest`, `EncryptedContextIT` (reads the raw column) |
| A tampered or moved entry fails the run with `CONTEXT_DECRYPTION_FAILED` and is never used | `RunCoordinator.failUnreadable` | `EncryptedContextIT` |
| Key rotation: new data uses the active key id; a startup report counts rows per key id | `ContextKeyReport` | `ContextCipherTest` |

## 5. Append-only activity log and grants (US-7.1, US-7.2)

| Control | Where | Evidence |
|---|---|---|
| `run_event`, `step_attempt` and `run_exchange` are insert-only for the application role: `UPDATE` and `DELETE` are denied (error 229) | `sqlserver/V5`–`V7` grants | `SchemaSqlServerIT` |
| Event `seq` is gap-free per run, under the run lock | `JdbcActivityRecorder` | `ActivityRecorderContract` (H2 and SQL Server) |
| Writes from a node that lost a run are fenced out | `JdbcRunLeaseService.lockFenced` | `CrashRecoveryIT`, `CrashRecoverySqlServerIT` |

## 6. Open points for the reviewer

1. **Raw values (Q7, US-7.4).** No API serves unmasked data. An investigation that needs a raw value means
   someone queries the production database directly, which is the least auditable path. Decide whether that is
   acceptable for R1, or whether it needs a story with its own endpoint and `PAYLOAD_ACCESSED` event.
2. **Circuit resets are logged, not stored.** A reset is logged at WARN with the actor and reason; it becomes an
   `endpoint_audit` row with US-2.3. Decide whether the log line is enough for R1.
3. **Downstream TLS.** `allow-http` is `false` outside `local`, and `allowed-hosts` limits where endpoints may
   point. Confirm the production host list.
4. **Callback signing keys** (US-6.1) arrive in R2; nothing in R1 accepts inbound callbacks.
5. **Boot 3.5 support** ended on 2026-06-30 (risk R1, accepted by the owner). Security fixes for the framework
   now depend on the commercial support path agreed in ADR-003.
6. **Database isolation in production.** `READ_COMMITTED_SNAPSHOT` is recommended on the handover list
   (US-8.5); the engine already retries transactions that lose to lock contention.

## 7. Outcome

_To be filled in at the review: date, attendees, decisions on §6, findings and their owners._
