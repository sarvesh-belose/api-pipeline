package com.crisil.apiorch.step;

/**
 * Runs one step execution (US-4.3). Implementations: {@code HttpStepExecutor} (SYNC, US-4.3),
 * {@code AsyncCallbackStepExecutor} (US-6.1) and the test-scope {@code StubStepExecutor} (US-4.1).
 *
 * <p>Called on a {@code step-io} worker thread, never inside a database transaction: no row lock may be held
 * across the network call (README §5.1, ArchUnit). It performs every attempt of the step (retries are inside, US-5.2),
 * records each attempt through the activity log, and returns a single {@link StepOutcome}. It never throws for a
 * downstream failure; a thrown exception means a bug, and the coordinator marks the step failed with
 * {@code INTERNAL_ERROR}. The coordinator, not the executor, writes the step's state transition and calls
 * {@code advance} again.
 */
public interface StepExecutor {

    StepOutcome execute(StepInvocation invocation);
}
