package com.crisil.apiorch.step;

/**
 * Records that an attempt is about to be sent (US-4.3, US-7.2): a short fenced transaction that appends
 * {@code REQUEST_SENT} and commits before the network call, so no lock is held across it (README §5.1). A node that
 * has lost the run gets {@link com.crisil.apiorch.engine.LeaseLostException} and sends nothing.
 */
public interface AttemptLog {

    /** Returns the {@code seq} of the {@code REQUEST_SENT} event, stored as the attempt's {@code event_seq}. */
    long requestSent(StepInvocation invocation, int attemptNo, String method);
}
