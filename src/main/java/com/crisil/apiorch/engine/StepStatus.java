package com.crisil.apiorch.engine;

/** Step status (README §7). Stored in {@code step_execution.status}. */
public enum StepStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, AWAITING_CALLBACK, CALLBACK_TIMED_OUT, CANCELLED, COMPENSATED, UNKNOWN;

    public boolean finished() {
        return this != PENDING && this != RUNNING && this != AWAITING_CALLBACK;
    }
}
