# US-4.4 Execute dependent and parallel steps (DAG)
**Epic:** E4 Run execution · **Priority:** Must · **Size:** M · **Depends on:** US-4.3 · **Release:** R1

## User story
As a **Pipeline Administrator**, I want to declare which steps depend on which, so that
independent APIs run in parallel and a step that needs several earlier results waits for all of
them, which makes runs faster without breaking data dependencies.

## Description
Each step's effective dependencies are either its explicit `dependsOn`, or the previous step in
the list when it has none (US-3.1). Together they form a DAG. The `ReadySetCalculator` returns
every `PENDING` step whose dependencies are all satisfied. All of those steps are submitted
together, up to `apiorch.run.max-parallel-steps` per run.

**Dependency rule** (README §7):
- A dependency is satisfied when it is `SUCCEEDED` or `SKIPPED(CONDITION_FALSE)`.
- If a dependency is `FAILED`, `CALLBACK_TIMED_OUT` or `SKIPPED(DEPENDENCY_FAILED)`, the
  dependent is `SKIPPED(DEPENDENCY_FAILED)`.

**Out of scope:** conditions (US-4.5) and failure policies (US-4.6). Those stories decide when a
step ends `FAILED` but the run continues.

## Acceptance criteria
- [ ] **AC1 (parallel):** Given api2 and api3 both have `"dependsOn": ["api1"]` and each WireMock
  stub delays 500 ms, when the run executes, then both start after api1 succeeds and their
  `[startedAt, endedAt]` intervals **overlap**. The api2/api3 stage takes less than the sum of
  the two delays plus 400 ms tolerance.
- [ ] **AC2 (join and fan-in):** Given api4 has `"dependsOn": ["api2","api3"]` and
  `"body": "{ 'kyc': steps.api2.body.ref, 'score': steps.api3.body.score }"`, when api2 finishes
  before api3, then api4 starts only after api3 finishes, and its body contains values from both.
- [ ] **AC3 (roots):** Given two steps with `"dependsOn": []`, when the run starts, then both are
  submitted immediately in list order.
- [ ] **AC4 (limit):** Given 15 independent root steps and `apiorch.run.max-parallel-steps=10`,
  when the run starts, then at most 10 requests are in flight at once. Each completion submits
  the next ready step.
- [ ] **AC5 (dependency failed):** Given api2 `FAILED` (or `UNKNOWN` or `CALLBACK_TIMED_OUT`)
  under a policy that lets the run continue (US-4.6), and api4 depends on api2, when the ready
  set is computed, then api4 becomes
  `SKIPPED` with `skip_reason = DEPENDENCY_FAILED` and the event `STEP_SKIPPED`
  `{reason, dependency: "api2"}`. Steps that depend on api4 are skipped transitively.
- [ ] **AC6 (concurrency safety):** Given two parallel steps completing at the same instant, when
  both call `advance`, then each ready step is submitted exactly once. No step executes twice,
  verified by WireMock request counts over 100 repeated runs.

## Technical details
**Components**
- `engine.ReadySetCalculator.compute(CompiledPipeline, Map<stepId, StepState>)` returns
  `ReadySet(ready[], skip[])`. It is pure and deterministic: the result is ordered by list
  position. It is used by the coordinator and the dry run (US-3.5).
- `engine.RunCoordinator.advance`:
  - Is serialized per run by the **`SqlDialect` row lock** of the fenced transaction (README
    §5.1, ADR-003 rule 4: SQL Server `WITH (UPDLOCK, ROWLOCK)`, H2 `FOR UPDATE`), not by an
    in-memory lock. This works the same whether two completions race on one node or a
    callback arrives on another.
  - Persists the `RUNNING` transitions **before** submitting, and submits after commit.
  - A completion callback (`whenComplete`) records the result in its own fenced transaction,
    then calls `advance` again.
  - In-flight count = rows in `RUNNING` for the run, read inside the locked transaction, and
    capped by `max-parallel-steps`.
  - Fencing (US-5.5) guarantees that only the lease holder writes.
- The context snapshot for a step contains only its **ancestors'** results (plus `input` and
  `run`). This matches the US-3.2 reference rule.

**Data:** `step_execution.skip_reason` (`CONDITION_FALSE | DEPENDENCY_FAILED`). The order
across parallel steps comes from `run_event.seq` (US-7.1).

**Configuration:** `apiorch.run.max-parallel-steps` (10).

**Logging:** DEBUG `ready set computed ready=[api2,api3] skipped=[]`; INFO `step skipped
reason=DEPENDENCY_FAILED dependency=api2`.

**Test notes**
- `ReadySetCalculatorTest` is table-driven: chain, diamond, multiple roots, failed dependency,
  condition-skipped dependency, and transitive skip.
- `ParallelStepsIT` uses WireMock `fixedDelay` and timing assertions with tolerance (AC1–AC3).
- `ParallelLimitIT` tracks maximum concurrency with a WireMock `ServeEventListener` counter (AC4).
- `DependencySkipIT` covers AC5.
- `AdvanceRaceIT` repeats a diamond pipeline 100 times and asserts exactly one request per step
  (AC6). It also runs in the `slow` tier on SQL Server, because a lock proven only on H2 is
  not evidence for SQL Server (ADR-003 F3, rule 6).
