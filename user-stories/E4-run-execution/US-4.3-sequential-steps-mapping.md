# US-4.3 Execute sequential steps, building requests from earlier responses
**Epic:** E4 Run execution · **Priority:** Must · **Size:** L · **Depends on:** US-4.1, US-7.1 · **Release:** R1

## User story
As a **Pipeline Administrator**, I want each step to call its downstream API in order, with its
URL, headers and body built from the inbound input and from fields, or parts of fields, of
earlier responses, so that API2 can use the customer ID returned by API1, API3 can use part of
API2's reference, and so on.

## Description
This is the core step execution. The `RunCoordinator` executes steps in list order by default
(each depends on the previous one; DAGs are US-4.4). For each step, `HttpStepExecutor` does the
following:
1. Builds a **context snapshot** (`input`, `steps.*` of ancestors, `run`).
2. Evaluates `path` interpolations, `query`, `headers` and `body` with JSONata.
3. Sends the request through the endpoint's HTTP client. Resilience is layered on in E5.
4. Parses the response and applies `successWhen` (default: 2xx).
5. Stores the response in the run context for later steps.
6. Updates `step_execution` and records activity events.

**Threading model (JDK 17, no virtual threads):**
- **Blocking HTTP calls run on `step-io`.** It is a **fixed-size** pool (core = max).
- **Never wait on step-io from inside it.** No task on `step-io` waits for another `step-io`
  task.
- **Completions advance the run directly.** A completion callback runs `advance` (short,
  fenced database work) on the completing thread.

**Out of scope:** parallel steps (US-4.4), failure policies other than the default `FAIL_RUN`
(US-4.6), resilience (E5), and attempt payload capture (US-7.2).

## Acceptance criteria
- [ ] **AC1:** Given steps api1→api5 in list order with no `dependsOn`, when a run executes, then
  exactly one request per step reaches WireMock, in the order api1, api2, api3, api4, api5. Each
  step starts only after the previous one is `SUCCEEDED`.
- [ ] **AC2 (whole-field mapping):** Given api1 returns `201 {"id":"C-77","segment":"RETAIL"}` and
  api2 has `"body": "{ 'customerId': steps.api1.body.id, 'name': input.name }"`, when api2 runs,
  then WireMock receives `{"customerId":"C-77","name":"Asha"}` with `Content-Type:
  application/json`.
- [ ] **AC3 (part-of-field mapping):** Given api1 returns
  `{"account":{"number":"ACC-0012345","holders":[{"pan":"ABCDE1234F"}]}}` with header
  `Location: /accounts/77`, and api2's body is `"{ 'branch': $substring(steps.api1.body.account.number, 4, 3),
  'pan': steps.api1.body.account.holders[0].pan, 'ref': steps.api1.headers.Location }"`, when api2
  runs, then WireMock receives `{"branch":"001","pan":"ABCDE1234F","ref":"/accounts/77"}`.
- [ ] **AC4:** Given `"path": "/scores/{steps.api1.body.id}"` and
  `"query": {"segment": "steps.api1.body.segment"}`, when the step runs, then the URL is
  `{baseUrl}/scores/C-77?segment=RETAIL`. A value `A/B` is encoded as `A%2FB`. A value that is
  empty, `.` or `..` fails the step with `EXPRESSION_ERROR` ("unsafe path segment"), so a
  response value can never move the request outside the endpoint's base path.
- [ ] **AC5:** Given every outbound step request, then it carries `X-Correlation-Id`,
  `X-Apiorch-Run-Id`, `X-Apiorch-Step-Id`, `Idempotency-Key: {runId}:{stepId}:{execNo}`,
  `Accept: application/json`, the endpoint auth (US-2.2) and the evaluated step `headers`.
  Step headers can't override `X-Apiorch-*` or `Idempotency-Key`.
- [ ] **AC6:** Given api2 responds `200 {"result":"ERROR"}` with
  `"successWhen": "body.result != 'ERROR'"`, the step is `FAILED` with `SUCCESS_CHECK_FAILED`.
  Given a non-2xx response without `successWhen`, it is `FAILED` with `HTTP_ERROR` and
  `httpStatus`. Under the default `FAIL_RUN`, pending steps become `CANCELLED` and the run
  becomes `FAILED`.
- [ ] **AC7:** Given a runtime expression error, or a response body above
  `apiorch.run.max-response-bytes`, when the step runs, then it is `FAILED` with
  `EXPRESSION_ERROR` (field and message) or `RESPONSE_TOO_LARGE`, and isn't retried. Given a
  non-JSON response, `steps.<id>.body` is a string. Given an empty body (204), it is `null`.
- [ ] **AC8 (activity sequence):** Given a five-step sequential run that succeeds, when it ends,
  then `run_event` contains exactly this sequence, gap-free (`seq` 1–23): `RUN_ACCEPTED`,
  `RUN_STARTED`; then `STEP_SCHEDULED`, `REQUEST_SENT`, `RESPONSE_RECEIVED`, `STEP_SUCCEEDED`
  once per step api1…api5; then `RUN_COMPLETED`. For the reordered version (`1,2,5,4,3`), the
  step blocks appear in that order.
- [ ] **AC9 (number precision, per ADR-002 D7):** Given api1 returns `{"id": 12345678901234567}`
  (above 2^53), when api2 maps `steps.api1.body.id` **unchanged**, then WireMock receives exactly
  `12345678901234567`, because pass-through keeps the `Long`. Given an expression that does
  arithmetic on it (`steps.api1.body.id + 0`) or `$number(...)`, the result is
  `12345678901234568`. That behaviour is documented and tested, so identifiers must not take
  part in arithmetic (US-3.1). Given `{"id": "12345678901234567"}`, the exact string is sent.
- [ ] **AC10 (expression guards, ADR-002 D1–D5):** Given 32 concurrent runs evaluating the same
  compiled expression, then every result matches its own run's input, because each evaluation
  uses a pooled instance and never a shared one. Given an expression that recurses without bound,
  then the step fails with `EXPRESSION_ERROR` (JSONata code included) within
  `apiorch.expression.timeout-ms` plus 500 ms, and its `step-io` thread is released. Given
  `$pad('x', 50000000)`, the step fails with `EXPRESSION_ERROR` (`D3999`) in under 1 s, because
  the built-in is overridden with a cap. Given an abort that arrives wrapped in a
  `RuntimeException` (a `$sort` comparator), it is still reported as `EXPRESSION_ERROR`.

## Technical details
**Components**
- `engine.RunCoordinator.advance(runId)` runs in a **fenced** transaction (README §5.1). It does
  the following:
  1. Locks the run row with the owner and epoch check.
  2. Loads the step states and clears `wakeup_requested`.
  3. Asks `ReadySetCalculator` (US-4.4) for ready steps.
  4. Marks them `RUNNING` with the `STEP_SCHEDULED` event.
  5. After commit, submits them to `step-io`.
  6. At quiescence, finalizes (US-4.6) or waits (US-6.3).

  Every completion calls `advance` again. No thread waits for a run.
- `step.StepExecutor` is an interface with the implementations `HttpStepExecutor` (SYNC),
  `AsyncCallbackStepExecutor` (US-6.1) and the test-scope `StubStepExecutor` (US-4.1).
- `step.RequestBuilder.build(compiledStep, snapshot)` returns
  `StepRequest(method, uri, headers, bodyBytes, bodyJson)`. It is pure, with no I/O, and is
  reused by the dry run (US-3.5).
- `expression.ExpressionEvaluator` is the interface the engine depends on, so the implementation
  chosen by the US-3.1 gate (`JsonataEvaluator`, `GuardedJsonataEvaluator` or
  `RestrictedExpressionEvaluator`) is swappable without touching the engine.
  **The chosen implementation is `GuardedJsonataEvaluator` (ADR-002).** It works as follows:
  - **D1, per-call isolation:** a `ConcurrentLinkedQueue<Jsonata>` pool per compiled expression.
    An evaluation borrows an instance, evaluates on a fresh `Frame` and returns it. Compiled
    instances write shared fields during `evaluate`, so they are never shared between
    concurrent evaluations. An extra instance costs about 25 µs warm.
  - **D2, bounds:** `frame.setRuntimeBounds(apiorch.expression.timeout-ms = 1000,
    apiorch.expression.max-depth = 200)` on every evaluation. The bound is **wall-clock**:
    100 ms produced 30–70 spurious aborts per 160,000 healthy evaluations under 32-thread load,
    and 1 s produced none, so the timeout is validated at 500–5000 ms.
  - **D3, built-in caps:** overrides registered on each pooled instance via `registerFunction`.
    `$pad` (width ≤ 10,000) is proven; the Sprint 1 audit adds the other argument-driven
    built-ins, and `BuiltinAbuseIT` (slow tier) runs each with extreme arguments under a
    watchdog.
  - **D5, error mapping:** any `Throwable` whose cause chain contains `JException` becomes
    `EXPRESSION_ERROR`, with the JSONata code and the position when ≥ 0.

  It converts inputs from and results to Jackson 2 `com.fasterxml.jackson.databind.JsonNode`; the round trip is lossless
  (ADR-002). An evaluation that exceeds its budget fails the step with `EXPRESSION_ERROR` and
  is **not** retried (AC7), and increments `apiorch.expression.budget_exceeded`, so a pipeline
  that is consistently near the limit is visible before it becomes an incident. A realistic
  mapping evaluates in about 10 µs.
- `expression.PathInterpolator` URL-encodes each interpolated segment, and rejects empty, `.`
  and `..` values. `http.UriBuilder` (US-2.1) assembles the URI with `pathSegment(...)` and
  verifies scheme, host, port and the base-path segment prefix.
- `http.ResilientHttpClient.execute(endpoint, StepRequest, attemptContext)` uses the endpoint
  runtime's `RestClient`. Here it makes a single attempt; E5 adds the decorators and the
  per-attempt timeout. The response is read through a counting, size-limited stream, which
  produces `RESPONSE_TOO_LARGE`.
- `step.ResponseParser` parses JSON for `application/json` and `+json`, and otherwise returns a
  UTF-8 string.
- `engine.RunContextStore` handles `run_context` (`input`, `<stepId>`, `output`). Encryption is
  US-7.6.

**Executor** (`config.ExecutorConfig`): a `ThreadPoolTaskExecutor` named `step-io` with
`corePoolSize = maxPoolSize = apiorch.executor.step-io.size` (64), queue capacity
`apiorch.executor.step-io.queue-capacity` (1000) and `allowCoreThreadTimeOut(false)`. A plain
`ThreadPoolExecutor` only grows beyond core when the queue is full, so the pool size is fixed.
It is decorated with `MdcTaskDecorator` (US-1.3).

**Data:** `step_execution` and `run_context`. `completion_seq` is the `seq` of the step's
`STEP_SUCCEEDED`, `STEP_FAILED` or `ASYNC_ACCEPTED` event, used for compensation order in
US-5.6, so it is never NULL for a step that has started.

**Configuration:** `apiorch.run.max-response-bytes` (5242880), `apiorch.executor.step-io.size`
(64) and `apiorch.executor.step-io.queue-capacity` (1000).

**Logging:** INFO `step started method=POST url=<masked url>`; INFO `step finished
status=SUCCEEDED httpStatus=201 durationMs=120 requestBytes=… responseBytes=…`. Bodies are never
logged by this story.

**Test notes**
- `SequentialRunIT` checks WireMock order and `equalToJson` bodies (AC1, AC2).
- `PartialFieldMappingIT` covers AC3.
- `PathInterpolationTest` covers encoding and the rejection of empty, `.` and `..` segments
  (AC4).
- `OutboundHeadersIT` covers AC5.
- `SuccessWhenIT` covers AC6.
- `ExpressionErrorIT`, `ResponseTooLargeIT` and `NonJsonResponseIT` cover AC7.
- `EventSequenceIT` covers v1 and v2 (AC8).
- `NumberPrecisionIT` covers AC9.
