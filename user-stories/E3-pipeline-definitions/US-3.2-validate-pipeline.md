# US-3.2 Validate a pipeline before publishing
**Epic:** E3 Pipeline definitions · **Priority:** Must · **Size:** M · **Depends on:** US-3.1 · **Release:** R1

## User story
As a **Pipeline Administrator**, I want apiorch to check my pipeline for broken order, missing
data dependencies, unsafe failure policies, unknown endpoints, unavailable features and invalid
expressions before it goes live, and to tell me in plain terms what order the steps will really
run in, so that a business-driven reorder can never publish a pipeline that fails or surprises
at runtime.

## Description
`DefinitionValidator` runs semantic rules over a definition and returns errors (which block
publish) and warnings (which don't). It is used:
- when a draft is saved (non-blocking, US-3.1);
- by the validate API (this story);
- at publish and rollback (US-3.3/3.4);
- at import (US-3.6);
- by the dry run (US-3.5).

There are three key rules:
1. **Reorder safety.** A step may read `steps.X` only if X is an **ancestor** of that step, and
   **every** reference into `steps` must be statically resolvable to a named step. Dynamic access
   (`$lookup(steps, …)`, a wildcard, a computed key) is an error, not a warning: the scanner cannot
   tell which step it reaches, so a single dynamic reference would turn "a reorder can never
   publish a pipeline that fails at runtime" into a best-effort claim. Nothing in the DSL needs it —
   a step that must read another step names it.
2. **Post-completion safety.** A step that may run after the caller was told the run is complete
   must not use `FAIL_RUN`. Post-completion steps are those that depend, directly or
   transitively, on an async step with `blocksRunCompletion: false`.
3. **Feature gate.** DSL features whose engine support hasn't shipped yet are rejected (README §3).

The response includes the **execution plan**: parallel groups in order, with post-completion
steps marked. It also includes warnings when list order and execution order differ.

**Out of scope:** checking that a downstream API accepts the payload. Use the dry run (US-3.5)
or a version-pinned test run (US-4.1) for that.

## Acceptance criteria
- [ ] **AC1 (valid reorder):** Given published v1 `api1→api2→api3→api4→api5` (implicit
  dependencies), where api3 and api4 read only `steps.api1` and `steps.api2`, when the draft
  reorders the list to `api1, api2, api5, api4, api3` and is validated with
  `POST /api/v1/admin/pipelines/{code}/validate`, then the response is `200` with
  `"valid": true` and `"executionPlan": [["api1"],["api2"],["api5"],["api4"],["api3"]]`.
- [ ] **AC2 (unsafe reorder):** Given the draft order `api1, api2, api5, api4, api3`, where
  api4's body reads `steps.api3.body.score`, when validated, then `"valid": false` with error
  `FORWARD_REFERENCE`, `stepId: "api4"`, `path: "/steps/3/body"` and the message `Step 'api4'
  reads steps.api3 but 'api3' is not an ancestor of 'api4' (it runs later). Move api3 before api4
  or add it to api4.dependsOn.`
- [ ] **AC3:** Given dependencies `a→b→c→a`, when validated, then error `CYCLE_DETECTED` lists
  `a → b → c → a`. Given `dependsOn` naming an unknown step or the step itself, the errors are
  `UNKNOWN_DEPENDENCY` and `SELF_DEPENDENCY`.
- [ ] **AC4:** Given a step whose `endpoint` is unregistered or disabled, when validated, then
  the error is `UNKNOWN_ENDPOINT` or `ENDPOINT_DISABLED`. Given a JSONata expression that doesn't
  compile, the error is `EXPRESSION_INVALID`, with the field path and the compiler message and
  position.
- [ ] **AC5 (post-completion):** Given api4 async with `blocksRunCompletion: false`, and api3
  depending on api4 (directly or through the list order) with the default `onFailure: FAIL_RUN`,
  when validated, then the error is `POST_COMPLETION_STEP_MUST_NOT_FAIL_RUN` for api3. The
  detailed executionPlan (`?detail=true`) marks api3 with `"postCompletion": true`, and the warning
  `STEP_RUNS_AFTER_COMPLETION` explains that api3 runs only after api4's callback. The same error
  applies to api4 itself if it uses `FAIL_RUN`.
- [ ] **AC6 (list vs execution order):** Given the list `api1, api2, api4(dependsOn:["api3"]),
  api3`, when validated, then it is valid, with the warning `LIST_ORDER_NOT_EXECUTION_ORDER`:
  "api4 is listed before api3 but runs after it". Given two steps that end up in the same plan
  level because of explicit `dependsOn`, the warning `STEPS_RUN_IN_PARALLEL` names them.
- [ ] **AC7 (feature gate):** Given a definition that uses a feature not yet enabled in this
  build, for example `mode: ASYNC_CALLBACK` before US-6.1, `when` before US-4.5, or `compensate`
  before US-5.6, when validated or published, then the error is `FEATURE_NOT_AVAILABLE`, with the
  JSON pointer and the name of the missing feature.
- [ ] **AC8:** Given a POST step with `retry.maxAttempts > 1` and `idempotent: false`, when
  validated, then the warning `RETRY_WITHOUT_IDEMPOTENCY` says that only failures before the
  request is sent will be retried.
- [ ] **AC10 (no dynamic step access):** Given an expression containing `$lookup(steps, $key)`,
  `steps.*` or `steps[$x]`, when validated, then it is `"valid": false` with
  `DYNAMIC_STEP_REFERENCE`, the field path, and the message `Step 'api4' reads steps dynamically,
  which cannot be checked against the execution order. Name the step directly, for example
  steps.api2.body.ref.` A literal bracket reference (`steps['api2']`) is resolved normally and
  stays valid.
- [ ] **AC9:** Given no draft and no request body, when validate is called, then the response is
  `404` `pipeline-not-found`. Given a body with a definition, it is validated **without being
  saved**. The response always includes `planDescription`: a plain-language list such as
  `"1. api1 (customer-svc POST /customers)  2. api2 … after api1  3. api5 and api4 in parallel
  after api2"`.

## Technical details
**Validation rules**
| Code | Sev | Rule |
|---|---|---|
| `SCHEMA_INVALID` | E | Structural / Bean Validation failure (US-3.1) |
| `CODE_MISMATCH` | E | `definition.code` ≠ path code |
| `DUPLICATE_STEP_ID` / `TOO_MANY_STEPS` | E | |
| `UNKNOWN_DEPENDENCY` / `SELF_DEPENDENCY` | E | `dependsOn` integrity |
| `CYCLE_DETECTED` | E | Kahn's topological sort leaves nodes; the cycle path is reported |
| `UNKNOWN_ENDPOINT` / `ENDPOINT_DISABLED` | E | Step or `compensate.endpoint` |
| `EXPRESSION_INVALID` | E | JSONata or `{…}` interpolation fails to compile. The JSONata code and position are reported (for example `S0203` at 25). A `StackOverflowError` from the parser is also reported here, never propagated (ADR-002 D4) |
| `EXPRESSION_TOO_LONG` / `EXPRESSION_TOO_DEEP` | E | Length above `apiorch.expression.max-length` (4096), or bracket nesting above `apiorch.expression.max-nesting` (64). Checked **before** the parser runs, because 5000 nested parentheses overflow the parser's stack (ADR-002 H10) |
| `REGEX_NOT_ALLOWED` | E | A regex literal `/…/` appears in any expression. Java's regex engine can backtrack without bound inside one call, which no in-process limit interrupts (ADR-002 D6; Business Owner sign-off pending) |
| `UNKNOWN_STEP_REFERENCE` | E | `steps.X` where X is not a step |
| `FORWARD_REFERENCE` | E | `steps.X` where X is not an ancestor |
| `SELF_REFERENCE` | E | A step reads `steps.<self>` outside `compensate` |
| `PATH_INVALID` | E | `path` doesn't start with `/`; contains scheme, host, `..`, `//`, `?` or `#`; or is over 2048 |
| `ASYNC_CALLBACK_MISSING` / `CALLBACK_ON_SYNC_STEP` | E | Async configuration |
| `POST_COMPLETION_STEP_MUST_NOT_FAIL_RUN` | E | A non-blocking async step, or any step depending on one, uses `FAIL_RUN` |
| `FALLBACK_MISSING` | E | `FALLBACK` without `fallback` |
| `FEATURE_NOT_AVAILABLE` | E | Field whose engine support is not in this build (`FeatureRegistry`) |
| `DSL_VERSION_UNSUPPORTED` | E | `dslVersion` above `apiorch.definition.dsl-version` for this build (US-3.1) |
| `RETRY_ON_INVALID` | E | `retryOn` has a 4xx other than 408/429, or an unknown token |
| `SYNC_WAIT_EXCEEDS_MAX` | E | `syncWaitMs` above `apiorch.run.max-sync-wait-ms` |
| `MASK_KEY_INVALID` | E | `maskKeys` entry doesn't match `^[A-Za-z0-9_\-]+(\.[A-Za-z0-9_\-]+\|\[\*\])*$` |
| `INPUT_SPEC_INVALID` | E | `input.validWhen` doesn't compile, or a `required` name is invalid |
| `RETRY_WITHOUT_IDEMPOTENCY` | W | See AC8 |
| `STEP_TIMEOUT_EXCEEDS_RUN_TIMEOUT` | W | |
| `BODY_ON_GET_OR_DELETE` | W | |
| `DYNAMIC_STEP_REFERENCE` | **E** | Dynamic access into `steps` (`$lookup(steps, …)`, `steps.*`, a computed key), which the scanner cannot resolve to an ancestor |
| `OUTPUT_REFERENCES_POST_COMPLETION_STEP` | W | `output` reads a post-completion step, so that value is missing at `COMPLETED_PENDING_ASYNC` |
| `STEP_RUNS_AFTER_COMPLETION` | W | Informs which steps are post-completion |
| `LIST_ORDER_NOT_EXECUTION_ORDER` | W | A step's list position is before an ancestor's |
| `STEPS_RUN_IN_PARALLEL` | W | Steps share a plan level because of explicit `dependsOn` |
| `EMPTY_ALLOWED_CALLERS` | W | No caller may start this pipeline (US-4.8) |
| `CAPTURE_LEVEL_NOT_SET` | W | No `logging.capture` at pipeline or step level; names the effective default (US-7.3) |

**Reference scanning (`expression.ReferenceScanner`):** token patterns
`\bsteps\.([a-z][a-z0-9_]*)` and `\bsteps\[\s*['"]([a-z][a-z0-9_]*)['"]\s*\]`, applied over every
expression field. Ancestors and descendants are transitive closures of the effective
`dependsOn`, with implicit defaults applied.
- The scanner is **deny-by-default** on `steps`: any occurrence of the token `steps` that one of
  those two patterns does not consume is reported as `DYNAMIC_STEP_REFERENCE`. A new JSONata
  construct therefore fails closed rather than slipping past an allow-list the scanner has not been
  taught about, which is what keeps the reorder guarantee sound as the DSL grows.

**Execution plan:** topological levels, ordered by list position within a level. Each entry can
be expanded as `{ "id": "api3", "postCompletion": true }` when the response is requested with
`?detail=true`. Without `detail`, the plan is arrays of ids (AC1).

**Feature gate:** `definition.FeatureRegistry` holds code constants `ASYNC_STEPS`,
`CONDITIONS` and `COMPENSATION`. Each constant is flipped to `true` by the story that delivers
the feature (US-6.1, US-4.5, US-5.6). They are **not** configuration, so production can't
enable half-built features.

**API contract:** `POST /api/v1/admin/pipelines/{code}/validate[?detail=true]`, with an optional
body `{ "definition": {…} }`. The response is `200`:
```json
{
  "valid": false,
  "checked": "draft",
  "executionPlan": [["api1"], ["api2"], ["api5"], ["api4"], ["api3"]],
  "planDescription": ["1. api1 (customer-svc POST /customers)", "2. api2 (kyc-svc POST /kyc) after api1", "…"],
  "errors": [ { "code": "FORWARD_REFERENCE", "severity": "ERROR", "stepId": "api4",
                "path": "/steps/3/body", "message": "Step 'api4' reads steps.api3 but …" } ],
  "warnings": []
}
```
Validation is pure: no database writes and no network calls.

**Components:** `definition.DefinitionValidator` aggregates `GraphRules`, `ReferenceRules`,
`PostCompletionRules`, `EndpointRules`, `ExpressionRules`, `AsyncRules`, `RetryRules`,
`LoggingRules`, `InputRules` and `FeatureRules`. `expression.JsonataCompiler` compiles the
expressions, and `definition.PlanDescriber` produces the plain-language plan.

**Test notes**
- `GraphRulesTest` covers cycles, unknown and self dependencies, plan levels, and the
  list-order and parallel warnings (AC3, AC6).
- `ReferenceRulesTest` covers the AC1/AC2 fixtures, literal bracket notation, the output rule, and
  the deny-by-default behaviour for `$lookup`, `steps.*`, a computed key and an unrecognised
  construct (AC10).
- `PostCompletionRulesTest` covers direct and transitive dependents, an implicit dependency
  through list order, and plan marking (AC5).
- `FeatureRulesTest` checks every gated field with its flag on and off (AC7).
- `ValidateApiIT` covers a draft, an ad-hoc body, the 404 case and `planDescription` (AC9).
- Every error code has a fixture at `src/test/resources/definitions/invalid/<CODE>.json`.
