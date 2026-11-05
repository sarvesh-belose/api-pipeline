package com.crisil.apiorch.engine;

import com.crisil.apiorch.definition.StepDefinition.OnFailure;

/** What the engine needs to know about one step of a run (a row of {@code step_execution}). */
public record StepState(String stepId, StepStatus status, int execNo, boolean failedWithStop) {

    public static StepState pending(String stepId) {
        return new StepState(stepId, StepStatus.PENDING, 1, false);
    }

    /** {@code failedWithStop}: failed and its policy is {@link OnFailure#FAIL_RUN}. */
    public static StepState failed(String stepId, boolean stops) {
        return new StepState(stepId, StepStatus.FAILED, 1, stops);
    }
}
