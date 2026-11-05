package com.crisil.apiorch.observability;

import java.util.Map;
import java.util.UUID;

/**
 * Appends one event to a run's activity log (US-7.1). Called inside the transaction that makes the state change
 * the event describes, after that transaction has locked the run row (README §5.1 rule 1), so the event and the
 * change commit or roll back together and {@code seq} is gap-free per run. A log line is written only after
 * commit (rule 8).
 */
public interface ActivityRecorder {

    /** Which step execution an event belongs to; {@code null} fields are not applicable. */
    record StepRef(String stepId, Integer execNo, Integer attempt) {

        public static StepRef of(String stepId, int execNo) {
            return new StepRef(stepId, execNo, null);
        }

        public static StepRef attempt(String stepId, int execNo, int attempt) {
            return new StepRef(stepId, execNo, attempt);
        }
    }

    /**
     * Records the event and returns its {@code seq}, which attempt and inbox rows store as {@code event_seq}.
     * {@code details} must hold only the catalog keys for {@code type}, never a request or response body (AC5).
     *
     * @param step  {@code null} for run-level events
     * @param actor the JWT principal for operator events, otherwise {@code null}
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a transaction (AC4)
     */
    long record(UUID runId, EventType type, StepRef step, Map<String, ?> details, String actor);
}
