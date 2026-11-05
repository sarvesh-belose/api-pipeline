package com.crisil.apiorch.engine;

/** Run status (README §7). Stored in {@code pipeline_run.status}. */
public enum RunStatus {
    ACCEPTED, RUNNING, WAITING_CALLBACK, COMPLETED_PENDING_ASYNC, COMPLETED, COMPLETED_WITH_ERRORS, FAILED,
    COMPENSATING, COMPENSATED, COMPENSATION_FAILED, CANCELLED, TIMED_OUT;

    /** The run will not change again (except post-completion steps of COMPLETED_PENDING_ASYNC). */
    public boolean terminal() {
        return switch (this) {
            case COMPLETED, COMPLETED_WITH_ERRORS, FAILED, COMPENSATED, COMPENSATION_FAILED, CANCELLED, TIMED_OUT -> true;
            default -> false;
        };
    }

    /** "Done for the caller" (README §7): a sync waiter answers 200 from here on. */
    public boolean doneForCaller() {
        return terminal() || this == COMPLETED_PENDING_ASYNC;
    }
}
