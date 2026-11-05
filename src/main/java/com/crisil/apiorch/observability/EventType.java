package com.crisil.apiorch.observability;

/**
 * The run event catalog (README §10, US-7.1). The owner story of each group is in brackets. {@link #warn()} marks
 * failures, logged at WARN instead of INFO (US-7.1 AC6). New types are appended; names are stored in
 * {@code run_event.event_type} (48 characters) and never renamed.
 */
public enum EventType {
    // Run lifecycle [4.1, 4.6, 5.1, 5.5, 5.7, 5.8]
    RUN_ACCEPTED, RUN_STARTED, RUN_COMPLETED, RUN_COMPLETED_WITH_ERRORS, RUN_FAILED(true), RUN_TIMED_OUT(true),
    RUN_RECOVERED, STEP_OUTCOME_UNKNOWN(true), RUN_CANCEL_REQUESTED, RUN_CANCELLED, STEP_RESOLVED, RUN_RESUMED,
    // Step execution [4.3, 4.4, 4.5, 4.6]
    STEP_SCHEDULED, REQUEST_SENT, RESPONSE_RECEIVED, STEP_SUCCEEDED, STEP_FAILED(true), STEP_SKIPPED,
    STEP_FALLBACK_APPLIED,
    // Resilience and auth [5.2, 5.3, 5.4, 2.2]
    ATTEMPT_FAILED(true), RETRY_SCHEDULED, CIRCUIT_OPEN_REJECTED(true), BULKHEAD_REJECTED(true), TOKEN_REFRESHED,
    // Compensation [5.6]
    COMPENSATION_STARTED, COMPENSATION_REQUEST_SENT, COMPENSATION_STEP_SUCCEEDED, COMPENSATION_STEP_FAILED(true),
    COMPENSATION_COMPLETED, COMPENSATION_FAILED(true),
    // Async steps and callbacks [6.1, 6.2, 6.3, 6.4]
    ASYNC_ACCEPTED, ASYNC_ACK_SUPERSEDED_BY_CALLBACK, CALLBACK_RECEIVED, CALLBACK_DUPLICATE, CALLBACK_LATE,
    CALLBACK_REJECTED(true), RUN_WAITING_CALLBACK, RUN_COMPLETED_PENDING_ASYNC, CALLBACK_TIMED_OUT(true),
    // Caller exchange and webhooks [6.5, 7.7]
    CALLER_WEBHOOK_SENT, CALLER_WEBHOOK_FAILED(true), CALLER_WEBHOOK_REDELIVERED, INBOUND_REQUEST_RECEIVED,
    CALLER_RESPONSE_SENT,
    // Data protection [7.4, 7.8, 7.10]
    PAYLOAD_ACCESSED, RUN_HOLD_SET, RUN_HOLD_CLEARED, RUN_DATA_ERASED;

    private final boolean warn;

    EventType() {
        this(false);
    }

    EventType(boolean warn) {
        this.warn = warn;
    }

    public boolean warn() {
        return warn;
    }
}
