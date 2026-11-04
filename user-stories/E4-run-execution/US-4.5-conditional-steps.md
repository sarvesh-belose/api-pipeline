# US-4.5 Skip steps conditionally
**Epic:** E4 Run execution · **Priority:** Should · **Size:** S · **Depends on:** US-4.3 · **Release:** R3

## User story
As a **Pipeline Administrator**, I want a step to run only when a condition on the input or
earlier responses is true, for example calling the enhanced-due-diligence API only for
high-risk customers, so that one pipeline serves several business cases without unnecessary
API calls.

## Description
A step's optional `when` is a JSONata expression over the run context. It is evaluated when the
step becomes ready. If the result is `false`, `null` or undefined, the step becomes
`SKIPPED(CONDITION_FALSE)` and no request is sent. Its dependents still run, because a
condition-skipped dependency counts as satisfied (README §7), and expressions that read the
skipped step's data get `undefined`.

**Out of scope:** routing to alternative branches. Express those as complementary `when`
conditions on sibling steps.

## Acceptance criteria
- [ ] **AC1:** Given api3 has `"when": "steps.api2.body.riskBand = 'HIGH'"` and api2 returns
  `riskBand: "LOW"`, when api3 becomes ready, then it is `SKIPPED` with `skip_reason =
  CONDITION_FALSE`, no request reaches the api3 WireMock stub, and the event `STEP_SKIPPED`
  `{reason: "CONDITION_FALSE"}` is recorded.
- [ ] **AC2:** Given the same pipeline with `riskBand: "HIGH"`, when api3 becomes ready, then it
  executes normally.
- [ ] **AC3:** Given api4 depends on a condition-skipped api3 and reads
  `steps.api3.body.score ? steps.api3.body.score : 0`, when api4 runs, then it executes with
  `score = 0`, and the run can end `COMPLETED`. A condition skip is not an error.
- [ ] **AC4:** Given a `when` that throws at runtime, when evaluated, then the step is `FAILED`
  with `EXPRESSION_ERROR` and the step's `onFailure` policy applies (US-4.6).
- [ ] **AC5:** Given a `when` that returns a non-boolean value, for example the string `"yes"`,
  when evaluated, then JSONata boolean casting applies: a non-empty string is true, and `0`,
  `""` and empty arrays are false. This behaviour is documented in the DSL reference (US-8.3).
- [ ] **AC6 (feature gate lifted):** Given this story is delivered, when a definition using
  `when` is validated, then `FEATURE_NOT_AVAILABLE` is no longer reported for it (US-3.2,
  `FeatureRegistry.CONDITIONS = true`). Before this story, such definitions can't be published.

## Technical details
**Components**
- `engine.ConditionEvaluator.shouldRun(compiledStep, contextSnapshot)` returns `RUN` or
  `SKIP_CONDITION_FALSE`, and throws `ExpressionEvaluationException`. It calls
  `JsonataEvaluator`, and its result is cast with JSONata's `$boolean` semantics.
- `RunCoordinator.advance` evaluates `when` for each ready step **before** marking it `RUNNING`.
  A skip is persisted the same way as any other transition.
- `ReadySetCalculator` (US-4.4) treats `SKIPPED(CONDITION_FALSE)` as satisfied.

**Data:** `step_execution.status = SKIPPED`, `skip_reason = CONDITION_FALSE`, and `started_at`
equal to `ended_at`. No `step_attempt` row is written.

**Validation:** compile errors are rejected at publish (`EXPRESSION_INVALID`, US-3.2), and `when`
references are checked by the ancestor rule (`FORWARD_REFERENCE`).

**Logging:** INFO `step skipped reason=CONDITION_FALSE`. The expression text is logged at DEBUG
only.

**Test notes**
- `ConditionEvaluatorTest` covers true, false, null, undefined, string, number and array casting
  (AC5).
- `ConditionalStepIT` runs the LOW and HIGH risk variants against WireMock and verifies counts
  (AC1, AC2), the dependent of a skipped step (AC3), and a runtime error (AC4).
