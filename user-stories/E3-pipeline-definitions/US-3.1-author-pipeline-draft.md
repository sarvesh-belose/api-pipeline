# US-3.1 Author a pipeline draft in the JSON DSL
**Epic:** E3 Pipeline definitions · **Priority:** Must · **Size:** L · **Depends on:** US-2.1 · **Release:** R1

## User story
As a **Pipeline Administrator** (a technical integration analyst), I want to describe a
pipeline as a JSON document listing its steps, their order and dependencies, and how each
request is built from earlier responses, and to save it as an editable draft, so that
orchestrations can be defined and changed without developers writing code.

## Description
A pipeline is one JSON document in the **apiorch DSL**. Each code has a row in `pipeline` (with
its on/off switch, US-3.7), **one editable draft** (stored as version `0`) and any number of
immutable published versions (US-3.3). This story defines the full DSL, the typed model and the
draft APIs.

**`dslVersion` is in the DSL from day one.** Published versions are immutable and are compiled when
they are read, so a run started a year from now still compiles a document written today. The moment
the DSL gains a field or changes a default, the compiler needs to know which revision it is reading —
and adding that marker later means guessing for every document already published. `dslVersion` costs
one integer now:
- A document without it is read as `dslVersion: 1`, and publishing stamps the current revision into
  the stored content, so every published version states its own revision explicitly.
- The compiler keeps a handler per revision it has ever supported, and **dropping support for one is
  a breaking change** that requires migrating the stored definitions first.
- A document whose `dslVersion` is higher than this build supports is rejected with
  `DSL_VERSION_UNSUPPORTED` (US-3.2) rather than being misread — which is what makes a rollback of
  the *service* safe after a DSL change.
- The current revision is `apiorch.definition.dsl-version`, a build constant and not configuration,
  for the same reason as `FeatureRegistry`.

Saving a draft enforces **structure only**: required fields, types, patterns and unknown fields.
Semantic checks are returned as non-blocking warnings, and become blocking at validate or
publish (US-3.2). The authoring kit (US-8.3) adds a JSON Schema and recipes for editors.

**Spike first, with a go/no-go: done, see [ADR-002](../../docs/adr/ADR-002-expression-engine.md)
(gate G2).** The outcome is **row 2 of the table below: JSONata 0.9.10 behind a
`GuardedJsonataEvaluator`**. The restricted fallback is not needed. In summary:
- **Bounds:** the library's own runtime bounds stop every hostile expression that runs through
  the evaluator. A single unbounded built-in (`$pad` to 50M) and deep nesting at compile are not
  stopped, and are closed by guards (ADR-002 D3, D4).
- **Threads:** compiled instances write shared fields during evaluation, so they are pooled per
  expression rather than shared (D1).
- **Time budget:** the bound is wall-clock, so the budget is 1 s, not 100 ms. Tighter budgets
  abort healthy expressions under load (D2).
- **Regex:** regex literals are not allowed in R1 (D6), pending Business Owner sign-off.

ADR-002's decisions D1–D7 are the contract for US-3.2 and US-4.3. `expression/README.md` links
to the ADR instead of repeating it.

A pipeline expression is **semi-trusted code submitted at runtime**: it is authored by a human,
published through an API, and then evaluated on every run on a thread of a fixed-size pool. An
expression that loops or recurses without bound is therefore a cluster-wide denial of service from
one publish. The spike is a **gate, not a note**, and it has three possible outcomes:

| Finding | Decision |
|---|---|
| The library enforces a time and depth bound in-process | Use it. `JsonataEvaluator` sets both (US-4.3) |
| It does not, but evaluation can be aborted cooperatively (an interruptible callback, a step counter) | Use it with a `GuardedJsonataEvaluator` wrapper that counts evaluation steps and aborts past the budget |
| Neither is possible | **Do not evaluate arbitrary JSONata on the request path.** Fall back to the restricted alternative below, and raise the difference with the Business Owner before US-3.2 starts |

**Fallback if the gate fails:** a restricted expression syntax — field paths over the context with
a fixed function allow-list (`substring`, `concat`, `uppercase`, `lowercase`, `number`, `string`,
`default`), no user-defined functions, no recursion and no iteration constructs. It is evaluated by
`RestrictedExpressionEvaluator`, is bounded by construction, and covers every expression in the
README §8 example and in US-8.3's recipes. The DSL field type does not change, so this is an engine
swap, not a DSL redesign.

Evaluating expressions on a separate thread with a hard timeout is **not** an option: it reinstates
a thread per evaluation, which the threading model in US-4.3 exists to avoid.

**Out of scope:** semantic rules (US-3.2), publishing (US-3.3) and executing the DSL (E4–E6).

## Acceptance criteria
- [ ] **AC1:** Given an admin and a structurally valid definition, when they
  `POST /api/v1/admin/pipelines` with `{ "code", "description", "definition" }`, then the
  response is `201` with `Location: /api/v1/admin/pipelines/{code}/draft` and `ETag`. A
  `pipeline` row (`enabled = 1`) and the draft (`version=0, status=DRAFT`) are stored with
  `created_by`.
- [ ] **AC2:** Given a code that already exists, when `POST` is used, then the response is `409`
  `already-exists`. An admin changes an existing pipeline with
  `PUT /api/v1/admin/pipelines/{code}/draft`, which creates the draft if none exists or replaces
  it. Replacing requires `If-Match`: a stale value returns `409` `version-conflict`, and a missing
  one returns `428` `precondition-required`.
- [ ] **AC3:** Given a definition with an unknown field (for example `"dependOn"`), a missing
  `steps`, a step without `endpoint`, `method` or `path`, a step id not matching
  `^[a-z][a-z0-9_]{0,63}$`, a code not matching `^[A-Z][A-Z0-9_]{2,63}$`, or an
  `allowedCallers` entry that is empty, when it is saved, then the response is `400`
  `validation-failed`. Each error has a JSON pointer, for example
  `/definition/steps/2/dependOn`.
- [ ] **AC4:** Given a structurally valid draft with semantic problems (for example a reference
  to an unknown step), when saved, then it is stored (`200`/`201`), and the response includes
  `"validation": {"valid": false, "errors": [...], "warnings": [...]}` from US-3.2.
- [ ] **AC5:** Given `GET /api/v1/admin/pipelines` by `apiorch.admin` or `apiorch.ops`, when
  called, then each pipeline is listed with `code`, `description`, `enabled`, `latestVersion`
  (null if never published), `latestPublishedAt`, `hasDraft` and `draftUpdatedAt`.
  `GET …/{code}/draft` returns the draft exactly as saved, with step order preserved, and
  `DELETE …/{code}/draft` removes it (`204`).
- [ ] **AC6:** Given more than `apiorch.definition.max-steps` (50) steps, when saved, then the
  response is `400` `validation-failed`. Given a request body over
  `apiorch.definition.max-document-bytes` (512 KB), it is `413` `payload-too-large` (US-1.1).
- [ ] **AC7:** Given any draft change, when it is saved or deleted, then an INFO log records the
  code, actor, row version and step-id list, without expression contents.
- [ ] **AC8 (`dslVersion`):** Given a definition with no `dslVersion`, when saved, then it is read as
  `1` and `GET …/draft` echoes it. Given `dslVersion: 1` with this build's revision at 1, it is
  accepted. Given a `dslVersion` above this build's revision, validate and publish return `422`
  `definition-invalid` with `DSL_VERSION_UNSUPPORTED`, naming both revisions, and the draft is still
  saved so it isn't lost on a service rollback.

## Technical details
**DSL reference: pipeline level**
| Field | Type | Req | Default | Notes |
|---|---|---|---|---|
| `dslVersion` | int ≥ 1 | | `1` on save; **stamped into every published version** | The DSL revision this document was written against (US-3.3) |
| `code` | string `^[A-Z][A-Z0-9_]{2,63}$` | ✔ | — | Must equal the path `{code}` |
| `description` | string ≤ 500 | | — | |
| `allowedCallers` | array of principal names | | `[]` = no caller allowed | US-4.8 (enforced from R2) |
| `input` | `{required: [names], validWhen: JSONata}` | | none | Checked before a run row is created (US-4.1) |
| `runTimeoutMs` | int 1 000–3 600 000 | | `apiorch.run.default-run-timeout-ms` | Segment deadline (US-5.1) |
| `syncWaitMs` | int 0–`apiorch.run.max-sync-wait-ms` | | `apiorch.run.default-sync-wait-ms` | US-4.1 |
| `logging` | `{capture: METADATA\|HEADERS\|FULL, maxBodyBytes, maskKeys[]}` | | global defaults | US-7.3 |
| `defaults` | `{timeoutMs, retry}` | | endpoint, then global | Inherited by steps |
| `steps` | array, 1..`max-steps` | ✔ | — | **List order = default execution order** |
| `output` | JSONata string | | `{ "<stepId>": steps.<stepId>.body, … }` for steps that succeeded | US-4.6 |

**DSL reference: step level**
| Field | Type | Req | Default | Notes |
|---|---|---|---|---|
| `id` | `^[a-z][a-z0-9_]{0,63}$`, unique | ✔ | — | |
| `description` | string ≤ 500 | | | |
| `endpoint` | endpoint `name` | ✔ | — | US-2.1 |
| `method` | `GET\|POST\|PUT\|PATCH\|DELETE` | ✔ | — | |
| `path` | starts with `/`, `{expr}` interpolation | ✔ | — | Segments built under the endpoint `baseUrl` |
| `query` | object `name → JSONata` | | — | An undefined result omits the param |
| `headers` | object `name → JSONata` | | — | Values become strings; undefined omits the header |
| `body` | JSONata string | | none | The result is serialized as JSON |
| `mode` | `SYNC\|ASYNC_CALLBACK` | | `SYNC` | US-6.1 (feature-gated until then) |
| `dependsOn` | array of step ids | | previous step in the list (none for the first) | `[]` = root step |
| `when` | JSONata → boolean | | always | US-4.5 (feature-gated) |
| `successWhen` | JSONata over `{status, headers, body}` | | any 2xx | Lets a 200 with an error body count as a failure |
| `timeoutMs` | int 100–120 000 | | `defaults.timeoutMs`, then the endpoint `readTimeoutMs` (resolved at attempt time) | US-5.1 |
| `retry` | `{maxAttempts 1–10, backoff FIXED\|EXPONENTIAL_JITTER, initialMs, maxMs, retryOn[]}` | | `defaults.retry`, then endpoint, then global | US-5.2 |
| `idempotent` | boolean | | `true` for GET/PUT/DELETE, `false` for POST/PATCH | US-5.2, US-5.5 |
| `onFailure` | `FAIL_RUN\|CONTINUE\|FALLBACK` | | `FAIL_RUN` | US-4.6; post-completion steps must not use `FAIL_RUN` (US-3.2) |
| `fallback` | JSONata over the run context plus `error` | if `FALLBACK` | — | Becomes `steps.<id>.body` |
| `compensate` | `{endpoint?, method, path, query?, headers?, body?}` | | — | Optional **per step**. US-5.6 (feature-gated) |
| `callback` | `{timeoutMs, successWhen}` | if `ASYNC_CALLBACK` | — | US-6.1, US-6.4 |
| `blocksRunCompletion` | boolean | | `true` | `false` lets the run complete while this step is pending (US-6.3) |
| `logging` | `{capture, maxBodyBytes, maskKeys[]}` | | pipeline `logging` | US-7.3 |

**Expression context** (JSONata, read-only):
```
input                                   inbound request body
steps.<id>.status | headers | body      response of a finished step (body = callback body for async steps)
steps.<id>.ack                          async acknowledgement response (US-6.1)
run.id | run.correlationId | run.pipeline | run.version
callback.url                            async steps only (US-6.1)
error.code | error.message | error.httpStatus   fallback only (US-4.6)
```
- `successWhen` and `callback.successWhen` see only the step's own `{status, headers, body}`.
- **Numbers** (ADR-002 D7, measured):
  - an integer that is only **passed through** (`steps.api1.body.id`) stays exact, even above
    2^53;
  - **arithmetic** (`id + 0`), `$number(...)` and any decimal value use doubles, so precision
    is lost above 2^53 and `0.1 + 0.2` is `0.30000000000000004`;
  - identifiers must therefore never take part in arithmetic, and monetary calculations belong
    to the downstream system, not to an expression (US-4.3 AC9).
- **No regex literals** (`/…/`) in R1 expressions (ADR-002 D6; rejected by US-3.2 as
  `REGEX_NOT_ALLOWED`). Use the string forms of `$contains`, `$split` and `$substring`.

**Example: five-step pipeline (API1→API2→API3→API4→API5)**
```json
{
  "dslVersion": 1,
  "code": "CUSTOMER_ONBOARD",
  "allowedCallers": ["crm-system"],
  "input": { "required": ["name", "pan"] },
  "steps": [
    { "id": "api1", "endpoint": "customer-svc", "method": "POST", "path": "/customers", "idempotent": true,
      "body": "{ 'name': input.name, 'pan': input.pan }" },
    { "id": "api2", "endpoint": "kyc-svc", "method": "POST", "path": "/kyc",
      "body": "{ 'customerId': steps.api1.body.id }" },
    { "id": "api3", "endpoint": "score-svc", "method": "GET", "path": "/scores/{steps.api1.body.id}" },
    { "id": "api4", "endpoint": "account-svc", "method": "POST", "path": "/accounts",
      "body": "{ 'customerId': steps.api1.body.id, 'kycRef': steps.api2.body.ref }" },
    { "id": "api5", "endpoint": "notify-svc", "method": "POST", "path": "/welcome",
      "body": "{ 'customerId': steps.api1.body.id }", "onFailure": "CONTINUE" }
  ]
}
```

**API contract**
- `POST /api/v1/admin/pipelines` takes `{ "code", "description", "definition": { …DSL… } }` and
  returns `201` with `{ code, version: 0, status: "DRAFT", rowVersion, updatedAt, updatedBy,
  validation }`.
- `PUT /api/v1/admin/pipelines/{code}/draft` takes the same body without `code`; send `If-Match`
  when replacing.
- `GET /api/v1/admin/pipelines`, `GET …/{code}/draft` and `DELETE …/{code}/draft`.

**Data:** Flyway `V3__pipeline.sql` creates `pipeline` (code PK, `enabled`, `disabled_reason`,
audit columns) and `pipeline_definition`, with UNIQUE(`code`, `version`) and an FK to
`pipeline`. Version `0` is the draft, so a single draft per code is guaranteed. `content` holds
the definition JSON as saved.

**Components** (`com.crisil.apiorch.definition`)
- `PipelineDefinition`, `StepDefinition`, `InputSpec`, `RetrySpec`, `CallbackSpec`,
  `CompensateSpec`, `LoggingSpec` and `DefaultsSpec` are Java records with Bean Validation.
- `DslJsonMapper` is a dedicated Jackson 2 `JsonMapper` (`com.fasterxml.jackson.databind`)
  built with `FAIL_ON_UNKNOWN_PROPERTIES` enabled, used only for the DSL. Jackson 2 mappers are
  mutable, so it is its own instance built by `JsonMapper.builder()`, and the shared
  Boot-managed mapper is never reconfigured. The setting is **load-bearing**: Boot's
  auto-configured `ObjectMapper` disables it (ADR-003 F7, measured in the probe), so without it AC3's `"dependOn"` typo would bind
  silently and the step would fall back to the implicit "previous step" dependency, which is
  exactly the reorder bug the DSL is meant to prevent. `DslBindingTest` asserts the mapper's
  setting directly as well as the `400`.
- **Persistence and API live in `com.crisil.apiorch.pipeline`** (implemented 2026-11-05):
  `DraftService` handles draft CRUD, ETag and If-Match, and calls `DefinitionValidator.validate()` for
  the `validation` block; `PipelineAdminController`, `PipelineEntity`, `PipelineDefinitionEntity` and
  their repositories sit beside it. They cannot be in `definition`, because the US-1.1 architecture
  rule keeps `definition` free of JDBC and repositories so the dry run (US-3.5) can reuse it.
  `definition` holds the records, `DslJsonMapper`, `DefinitionBinder` (structure, JSON pointers) and
  `DefinitionValidator` (the US-3.1 semantic subset: unknown steps, cycles, unknown endpoints,
  duplicate ids, required `fallback`/`callback`, `DSL_VERSION_UNSUPPORTED`, list-order warnings).
  US-3.2 adds expression compilation, reorder safety and feature gates.

**Test notes**
- `DslBindingTest` uses golden JSON: valid, unknown field, bad id, bad code, too many steps, an
  empty `allowedCallers` entry, and pointer paths (AC3, AC6). It also covers an absent, equal and
  too-high `dslVersion` (AC8).
- `DraftApiIT` covers create, already-exists, PUT create and replace, If-Match 409/428, list
  (with the ops role), get and delete (AC1, AC2, AC5).
- `DraftSaveWarningsIT` covers AC4.
- `JsonataSpikeTest` (with `GuardedJsonataEvaluator`, US-3.2/US-4.3; until then the executable
  findings stay in `tools/stack-probe-b35-mssql` and ADR-002) keeps the spike's executable findings: thread safety under 32 threads,
  bounds, and number precision. It includes a **hostile expression suite** — unbounded recursion,
  a large cross-product, deep nesting and a pathological string build — and asserts that each one
  is aborted within the budget rather than running to exhaustion. If any of them cannot be aborted,
  the gate above has failed and the fallback applies.
