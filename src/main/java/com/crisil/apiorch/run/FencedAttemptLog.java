package com.crisil.apiorch.run;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.crisil.apiorch.engine.RunLeaseService;
import com.crisil.apiorch.observability.ActivityRecorder;
import com.crisil.apiorch.observability.ActivityRecorder.StepRef;
import com.crisil.apiorch.observability.EventType;
import com.crisil.apiorch.step.AttemptLog;
import com.crisil.apiorch.step.StepInvocation;

/**
 * {@code REQUEST_SENT} in its own short fenced transaction, committed before the call is made (US-4.3); repeated if
 * it loses to lock contention.
 */
@Component
public class FencedAttemptLog implements AttemptLog {

    private final TransactionTemplate tx;
    private final RunLeaseService leases;
    private final ActivityRecorder recorder;

    public FencedAttemptLog(TransactionTemplate tx, RunLeaseService leases, ActivityRecorder recorder) {
        this.tx = tx;
        this.leases = leases;
        this.recorder = recorder;
    }

    @Override
    public long requestSent(StepInvocation invocation, int attemptNo, String method) {
        return com.crisil.apiorch.persistence.LockRetry.run(() -> tx.execute(s -> {
            leases.lockFenced(invocation.lease());
            return recorder.record(invocation.runId(), EventType.REQUEST_SENT,
                    StepRef.attempt(invocation.step().id(), invocation.execNo(), attemptNo),
                    Map.of("endpoint", invocation.step().definition().endpoint(), "method", method,
                            "attempt", attemptNo), null);
        }));
    }
}
