# US-3.5 Dry-run a version with sample input and mocked responses
**Epic:** E3 Pipeline definitions · **Priority:** Should · **Size:** M · **Depends on:** US-3.3, US-4.3 · **Release:** R3

## User story
As a **Pipeline Administrator**, I want to run a draft or a published version against sample
input and mocked responses, and see the exact request each step would send in execution order,
so that I can confirm a reorder or mapping change is correct before any real API is called.

## Description
The dry run executes the pipeline's **control flow and mappings** in memory, using the same
`CompiledPipeline` and JSONata evaluator as real runs. Downstream calls are replaced by mocks
supplied in the request.

The dry run persists nothing, makes no network calls, resolves no secrets and applies no
retries. It does apply `when`, `successWhen`, `onFailure` (`CONTINUE`, `FALLBACK`, `FAIL_RUN`),
async acknowledgements and callbacks, and `output`.

**Out of scope:** timing or resilience simulation, and mocks for compensation calls.

## Acceptance criteria
- [ ] **AC1:** Given a draft and input `{"name":"Asha","pan":"ABCDE1234F"}` with mocks for api1
  (`201 {"id":"C-77"}`) and api2, when an admin sends
  `POST /api/v1/admin/pipelines/{code}/draft/dry-run`, then the response lists each step in
  execution order with its resolved `method`, full `url`, `headers` and `body`. For example,
  api2's body is `{"customerId":"C-77"}`. The response also contains the `executionPlan` and the
  final `output`.
- [ ] **AC2:** Given a published version, when an admin sends `POST …/versions/{v}/dry-run`, then
  that version is used. Given a reordered draft (`1,2,5,4,3`), the step list in the response
  follows the new plan.
- [ ] **AC3:** Given a mock for api3 of `{"status":503}` and api3 configured with
  `onFailure: CONTINUE`, when dry-run, then api3 is `FAILED`, its dependents are
  `SKIPPED (DEPENDENCY_FAILED)`, and the simulated run status is `COMPLETED_WITH_ERRORS`. With
  `FALLBACK`, the fallback body is shown and dependents receive it.
- [ ] **AC4:** Given an async step with mock `{"ack":{"status":202},"callback":{"body":{"status":"DONE"}}}`,
  when dry-run, then the step shows its `callback.url` placeholder and its `successWhen` result.
  With `"callback": null`, the step is shown as `CALLBACK_TIMED_OUT` and its policy is applied.
- [ ] **AC5:** Given a step without a mock, when dry-run, then the default mock
  `{"status":200,"body":{}}` is used and the warning `MOCK_MISSING` is added. Given an expression
  runtime error, the step shows `FAILED` with `EXPRESSION_ERROR`, the message and the policy
  applied.
- [ ] **AC6:** Given any dry run, then no `pipeline_run`, `run_event` or `step_attempt` row is
  written, and no HTTP call leaves apiorch (asserted with WireMock: zero requests). Auth headers
  appear as placeholders such as `"Authorization": "<BEARER from env:CORE_TOKEN>"`. Fields
  matching mask rules are masked in the response, the same way as in logs (US-7.3).

## Technical details
**API contract:** `POST /api/v1/admin/pipelines/{code}/draft/dry-run` or
`POST …/{code}/versions/{v}/dry-run`. Request:
```json
{
  "input": { "name": "Asha", "pan": "ABCDE1234F" },
  "mocks": {
    "api1": { "status": 201, "headers": { "Content-Type": "application/json" }, "body": { "id": "C-77" } },
    "api3": { "status": 503 },
    "api5": { "ack": { "status": 202, "body": { "jobId": "J1" } }, "callback": { "body": { "status": "DONE" } } }
  }
}
```
Response `200`:
```json
{
  "version": 0, "simulatedStatus": "COMPLETED_WITH_ERRORS",
  "executionPlan": [["api1"],["api2"],["api5"],["api4"],["api3"]],
  "steps": [
    { "id": "api1", "status": "SUCCEEDED",
      "request": { "method": "POST", "url": "https://customer.internal/api/customers",
                   "headers": { "Authorization": "<BEARER from env:CUST_TOKEN>", "Idempotency-Key": "<runId>:api1:1" },
                   "body": { "name": "Asha", "pan": "***" } },
      "response": { "status": 201, "body": { "id": "C-77" } } }
  ],
  "output": { "customerId": "C-77" },
  "warnings": [ { "code": "MOCK_MISSING", "stepId": "api4" } ]
}
```
If the definition has validation errors, the response is `422` `definition-invalid` (US-3.2).

**Components:**
- `definition.DryRunService` reuses `engine.ReadySetCalculator` (US-4.4), `step.RequestBuilder`
  (US-4.3), `expression.JsonataEvaluator`, the policy logic of `engine.FailurePolicyApplier`
  (US-4.6), and `observability.PayloadMasker` (US-7.3). Control flow runs single-threaded in
  plan order. The engine classes must therefore keep these pieces free of I/O, so the dry run can
  share them.
- Run placeholders: `run.id = "<runId>"` and `callback.url =
  "<callback-url:{stepId}>"`.

**Security:** requires `apiorch.admin`, and the response is masked (AC6).

**Test notes**
- `DryRunIT` covers the AC1–AC5 fixtures, plus `WireMock.verify(0, anyRequestedFor(anyUrl()))`
  and row counts unchanged (AC6).
- `DryRunParityTest` runs the same fixture through the dry run and through a real run against
  WireMock with the same responses, and asserts identical request bodies (guards against drift).
