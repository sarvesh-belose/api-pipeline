package com.crisil.apiorch.run;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.config.InstanceIdentity;
import com.crisil.apiorch.engine.Lease;
import com.crisil.apiorch.engine.RunContext;
import com.crisil.apiorch.engine.RunLeaseService;
import com.crisil.apiorch.engine.RunStatus;
import com.crisil.apiorch.engine.StepStatus;
import com.crisil.apiorch.observability.ActivityRecorder;
import com.crisil.apiorch.observability.ActivityRecorder.StepRef;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.EventType;
import com.crisil.apiorch.observability.ProblemTypes;
import com.crisil.apiorch.persistence.JdbcRunLeaseService;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.run.dto.RunResource;

/**
 * Operator cancel and resolve (US-5.7), for {@code apiorch.ops}, each with an audit reason. Cancel persists
 * {@code stop_reason = CANCEL} and a wake-up under the run lock; after commit this node advances the run if it drives
 * it, or claims it if nobody does; otherwise the driving node stops at its next transaction. In-flight steps keep
 * their real outcome. Resolve records an operator's verdict on an {@code UNKNOWN} step and does not advance the run.
 */
@Service
public class RunOperatorService {

    private static final Logger log = LoggerFactory.getLogger(RunOperatorService.class);

    static final String ILLEGAL_STATE = "illegal-run-state";
    static final String CANCEL = "CANCEL";

    private final TransactionTemplate tx;
    private final JdbcRunLeaseService leases;
    private final RunLeaseService claims;
    private final RunRows rows;
    private final ActivityRecorder recorder;
    private final RunCoordinator coordinator;
    private final AdmissionControl admission;
    private final RunResourceAssembler assembler;
    private final String me;

    public RunOperatorService(TransactionTemplate tx, JdbcRunLeaseService leases, RunLeaseService claims, RunRows rows,
            ActivityRecorder recorder, RunCoordinator coordinator, AdmissionControl admission,
            RunResourceAssembler assembler, InstanceIdentity instance) {
        this.tx = tx;
        this.leases = leases;
        this.claims = claims;
        this.rows = rows;
        this.recorder = recorder;
        this.coordinator = coordinator;
        this.admission = admission;
        this.assembler = assembler;
        this.me = instance.id();
    }

    /** AC1–AC4: {@code 202} with the run resource; the cancellation completes asynchronously. */
    public RunResource cancel(UUID runId, String reason, boolean compensate, String actor) {
        requireReason(reason);
        if (compensate) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "compensation not available (US-5.6)");
        }
        rows.run(runId).orElseThrow(() -> RunResourceAssembler.notFound(runId));
        RunRows.Run before = tx.execute(s -> {
            leases.lockUnowned(runId);
            RunRows.Run run = rows.run(runId).orElseThrow(() -> RunResourceAssembler.notFound(runId));
            if (run.status().terminal() || run.status() == RunStatus.COMPENSATING) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ILLEGAL_STATE,
                        "Run " + runId + " is " + run.status() + " and cannot be cancelled.")
                        .with("status", run.status().name());
            }
            rows.stop(runId, CANCEL, null, null, null);
            rows.requestWakeup(runId);
            recorder.record(runId, EventType.RUN_CANCEL_REQUESTED, null, Map.of("reason", reason), actor);
            log.warn("operator action=CANCEL actor={} reason={} runId={}", actor, reason, runId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    act(runId);
                }
            });
            return run;
        });
        return assembler.assemble(rows.run(runId).orElse(before));
    }

    /** The cancel takes effect now if this node drives the run, or nobody does (AC3). */
    private void act(UUID runId) {
        RunRows.Run run = rows.run(runId).orElse(null);
        if (run == null || run.status().terminal()) {
            return;
        }
        if (me.equals(run.ownerInstance())) {
            coordinator.advance(new Lease(runId, me, run.leaseEpoch()));
            return;
        }
        Optional<Lease> claimed = claims.claim(runId);
        if (claimed.isPresent()) {
            admission.claimed();
            coordinator.recover(claimed.get(), run.ownerInstance());
        }
        // otherwise another node drives it and stops at its next fenced transaction
    }

    /** AC5, AC6: an operator's verdict on an {@code UNKNOWN} step; the run is not advanced. */
    public RunResource.Step resolve(UUID runId, String stepId, String outcome, JsonNode body, Integer httpStatus,
            String reason, String actor) {
        requireReason(reason);
        if (!"SUCCEEDED".equals(outcome) && !"FAILED".equals(outcome)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "outcome must be SUCCEEDED or FAILED");
        }
        rows.run(runId).orElseThrow(() -> RunResourceAssembler.notFound(runId));
        return tx.execute(s -> {
            leases.lockUnowned(runId);
            RunRows.Step step = rows.steps(runId).stream().filter(st -> st.stepId().equals(stepId)).findFirst()
                    .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "step-not-found",
                            "Run " + runId + " has no step " + stepId + "."));
            if (step.status() != StepStatus.UNKNOWN) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ILLEGAL_STATE,
                        "Step " + stepId + " is " + step.status() + "; only UNKNOWN steps can be resolved.")
                        .with("status", step.status().name());
            }
            long seq = recorder.record(runId, EventType.STEP_RESOLVED, StepRef.of(stepId, step.execNo()),
                    Map.of("reason", reason, "outcome", outcome), actor);
            if (outcome.equals("SUCCEEDED")) {
                rows.putContext(runId, stepId, RunContext.stepResult(httpStatus, null, body));
                rows.finishStep(runId, stepId, StepStatus.SUCCEEDED, step.attempts(), httpStatus, step.durationMs(),
                        null, null, false, seq);
            } else {
                rows.finishStep(runId, stepId, StepStatus.FAILED, step.attempts(), httpStatus, step.durationMs(),
                        "RESOLVED_FAILED", reason, false, seq);
            }
            log.warn("operator action=RESOLVE actor={} reason={} runId={} stepId={} outcome={}", actor, reason, runId,
                    stepId, outcome);
            RunRows.Step after = rows.steps(runId).stream().filter(st -> st.stepId().equals(stepId)).findFirst()
                    .orElseThrow();
            return new RunResource.Step(after.stepId(), after.status().name(), after.attempts(), after.httpStatus(),
                    after.durationMs(), after.fallbackApplied(), after.skipReason());
        });
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "reason is required, 1 to 500 characters");
        }
    }
}
