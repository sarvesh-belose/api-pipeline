package com.crisil.apiorch.run;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.config.ExecutorConfig;
import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.definition.StepDefinition.OnFailure;
import com.crisil.apiorch.engine.FailurePolicyApplier;
import com.crisil.apiorch.engine.Lease;
import com.crisil.apiorch.engine.LeaseLostException;
import com.crisil.apiorch.engine.RunContext;
import com.crisil.apiorch.engine.RunDecision;
import com.crisil.apiorch.engine.RunLeaseService;
import com.crisil.apiorch.engine.RunStatus;
import com.crisil.apiorch.engine.StepState;
import com.crisil.apiorch.engine.StepStatus;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;
import com.crisil.apiorch.observability.ActivityRecorder;
import com.crisil.apiorch.observability.ApiorchMetrics;
import com.crisil.apiorch.observability.ActivityRecorder.StepRef;
import com.crisil.apiorch.observability.AttemptRecord;
import com.crisil.apiorch.observability.AttemptWriter;
import com.crisil.apiorch.observability.CaptureSettings;
import com.crisil.apiorch.observability.EventType;
import com.crisil.apiorch.observability.MdcSupport;
import com.crisil.apiorch.persistence.ContextCipher;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.step.HttpStepExecutor;
import com.crisil.apiorch.step.StepAttempt;
import com.crisil.apiorch.step.StepExecutor;
import com.crisil.apiorch.step.StepInvocation;
import com.crisil.apiorch.step.StepOutcome;

/**
 * Drives runs, event by event, with no thread per run (US-4.1, US-4.3, README §5.1). Every write transaction starts
 * with the fenced run-row lock; {@link #advance} schedules the ready steps and submits them to {@code step-io}
 * after commit; each step's completion is recorded in its own transaction and calls {@code advance} again. At
 * quiescence the run is finalized and sync waiters are woken after commit. Decisions come from the pure
 * {@link RunDecision}. US-4.6, US-5.1 and US-5.5 extend the failure, deadline and recovery paths.
 */
@Component
public class RunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);

    static final String STEP_FAILED = "STEP_FAILED";
    static final String DEADLINE = "DEADLINE";
    static final int LOCK_RETRIES = 5;
    static final String RUN_TIMEOUT = "RUN_TIMEOUT";
    static final String OUTPUT_EXPRESSION_ERROR = "OUTPUT_EXPRESSION_ERROR";

    private final TransactionTemplate tx;
    private final RunRows rows;
    private final RunLeaseService leases;
    private final ActivityRecorder recorder;
    private final PublishedPipelines pipelines;
    private final StepExecutor executor;
    private final ThreadPoolTaskExecutor stepIo;
    private final RunCompletionNotifier notifier;
    private final ExpressionEvaluator evaluator;
    private final AttemptWriter attempts;
    private final CapturePolicies captures;
    private final ApiorchMetrics metrics;
    private final CallerExchangeLog exchanges;
    private final DeadlineTimers deadlines;
    private final Clock clock;
    private final long defaultRunTimeoutMs;
    private final int maxParallelSteps;

    public RunCoordinator(TransactionTemplate tx, RunRows rows, RunLeaseService leases, ActivityRecorder recorder,
            PublishedPipelines pipelines, StepExecutor executor,
            @Qualifier(ExecutorConfig.STEP_IO) ThreadPoolTaskExecutor stepIo, RunCompletionNotifier notifier,
            ExpressionEvaluator evaluator, AttemptWriter attempts, CapturePolicies captures,
            ApiorchMetrics metrics, CallerExchangeLog exchanges, DeadlineTimers deadlines, Clock clock,
            ApiorchProperties properties) {
        this.tx = tx;
        this.rows = rows;
        this.leases = leases;
        this.recorder = recorder;
        this.pipelines = pipelines;
        this.executor = executor;
        this.stepIo = stepIo;
        this.notifier = notifier;
        this.evaluator = evaluator;
        this.attempts = attempts;
        this.captures = captures;
        this.metrics = metrics;
        this.exchanges = exchanges;
        this.deadlines = deadlines;
        this.clock = clock;
        this.defaultRunTimeoutMs = properties.run().defaultRunTimeoutMs();
        this.maxParallelSteps = properties.run().maxParallelSteps();
    }

    /** {@code ACCEPTED → RUNNING} with {@code RUN_STARTED}, then the first {@link #advance}. */
    public void start(Lease lease) {
        inTx(s -> {
            leases.lockFenced(lease);
            RunRows.Run run = rows.run(lease.runId()).orElseThrow();
            CompiledPipeline pipeline = pipelines.compiled(run.pipelineCode(), run.pipelineVersion());
            long timeout = pipeline.definition().runTimeoutMs() != null ? pipeline.definition().runTimeoutMs()
                    : defaultRunTimeoutMs;
            rows.markRunning(lease.runId(), timeout);
            recorder.record(lease.runId(), EventType.RUN_STARTED, null, Map.of("runTimeoutMs", timeout), null);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deadlines.schedule(lease.runId(), timeout, () -> deadlineReached(lease));
                }
            });
            return null;
        });
        advance(lease);
    }

    /** Schedules every ready step, or finalizes the run at quiescence. Safe to call concurrently. */
    public void advance(Lease lease) {
        List<StepInvocation> scheduled;
        try {
            scheduled = inTx(s -> schedule(lease));
        } catch (ContextCipher.DecryptionException e) {
            log.error("run context failed to decrypt runId={}; failing the run", lease.runId(), e);
            failUnreadable(lease, e);
            return;
        } catch (LeaseLostException e) {
            log.warn("lease lost runId={} epoch={}", lease.runId(), lease.epoch());
            metrics.leaseLost();
            return;
        } catch (RuntimeException e) {
            releaseForRecovery(lease, "advance", e);
            return;
        }
        for (StepInvocation invocation : scheduled) {
            stepIo.execute(() -> run(invocation));
        }
    }

    private List<StepInvocation> schedule(Lease lease) {
        leases.lockFenced(lease);
        RunRows.Run run = rows.run(lease.runId()).orElseThrow();
        if (run.status().terminal()) {
            return List.of();
        }
        CompiledPipeline pipeline = pipelines.compiled(run.pipelineCode(), run.pipelineVersion());
        List<RunRows.Step> steps = rows.steps(run.id());
        Map<String, StepState> states = new LinkedHashMap<>();
        for (RunRows.Step step : steps) {
            boolean stops = step.status() == StepStatus.FAILED
                    && pipeline.step(step.stepId()).definition().effectiveOnFailure() == OnFailure.FAIL_RUN;
            states.put(step.stepId(), new StepState(step.stepId(), step.status(), step.execNo(), stops));
        }
        String stopReason = run.stopReason();
        long remaining = rows.remainingMs(run.id()).orElse(Long.MAX_VALUE);
        if (stopReason == null && remaining <= 0) {
            // the timer may be lost (another node, a restart): advance enforces the deadline itself
            rows.stop(run.id(), DEADLINE, RUN_TIMEOUT, "Run deadline reached", null);
            stopReason = DEADLINE;
        }
        RunDecision.Next next = RunDecision.decide(pipeline, states, stopReason != null);
        for (RunDecision.Skip skip : next.skip()) {
            if (skip.reason().equals("RUN_STOPPED")) {
                rows.cancelStep(run.id(), skip.stepId());
            } else {
                rows.skipStep(run.id(), skip.stepId(), skip.reason());
            }
            recorder.record(run.id(), EventType.STEP_SKIPPED, StepRef.of(skip.stepId(), 1),
                    details("reason", skip.reason(), "dependency", skip.dependency()), null);
            if (skip.dependency() != null) {
                log.info("step skipped stepId={} reason={} dependency={}", skip.stepId(), skip.reason(),
                        skip.dependency());
            }
        }
        if (next.quiescent()) {
            boolean degraded = steps.stream().anyMatch(RunRows.Step::fallbackApplied);
            RunStatus status = next.finalStatus() == RunStatus.COMPLETED && degraded
                    ? RunStatus.COMPLETED_WITH_ERRORS : next.finalStatus();
            if (DEADLINE.equals(stopReason)) {
                // US-5.1 AC3: a normal segment past its deadline (post-completion segments come with US-6.3)
                status = RunStatus.TIMED_OUT;
            }
            finish(run, pipeline, status, steps.size());
            return List.of();
        }
        // AC4: at most max-parallel-steps in flight; each completion advances and submits the next
        long inFlight = steps.stream().filter(st -> st.status() == StepStatus.RUNNING).count();
        List<CompiledStep> ready = next.ready().stream()
                .limit(Math.max(0, maxParallelSteps - inFlight)).toList();
        log.debug("ready set computed ready={} skipped={} inFlight={}", next.ready().stream().map(CompiledStep::id)
                .toList(), next.skip().stream().map(RunDecision.Skip::stepId).toList(), inFlight);
        if (ready.isEmpty()) {
            return List.of();
        }
        ObjectNode context = context(run);
        Instant deadline = remaining == Long.MAX_VALUE ? null : Instant.now(clock).plusMillis(remaining);
        List<StepInvocation> out = new ArrayList<>();
        for (CompiledStep step : ready) {
            rows.markStepRunning(run.id(), step.id());
            recorder.record(run.id(), EventType.STEP_SCHEDULED, StepRef.of(step.id(), 1), Map.of("stepId", step.id(),
                    "execNo", 1, "endpoint", step.definition().endpoint(), "mode", step.definition().effectiveMode().name()),
                    null);
            Integer stepTimeout = step.definition().timeoutMs() != null ? step.definition().timeoutMs()
                    : pipeline.definition().defaults() != null ? pipeline.definition().defaults().timeoutMs() : null;
            out.add(new StepInvocation(lease, run.pipelineCode(), run.pipelineVersion(), run.correlationId(), step, 1,
                    snapshot(context, pipeline, step), deadline, stepTimeout));
        }
        return out;
    }

    private void run(StepInvocation invocation) {
        StepOutcome outcome;
        try (MdcSupport.Scope runScope = MdcSupport.withRun(invocation.runId(), invocation.pipeline(),
                invocation.version(), invocation.correlationId());
                MdcSupport.Scope stepScope = MdcSupport.withStep(invocation.step().id(), 1)) {
            try {
                outcome = executor.execute(invocation);
            } catch (LeaseLostException e) {
                log.warn("lease lost runId={} epoch={}; {} not sent", invocation.runId(), invocation.lease().epoch(),
                        invocation.step().id());
                metrics.leaseLost();
                return;
            } catch (RuntimeException e) {
                log.error("step executor failed unexpectedly", e);
                outcome = StepOutcome.failed(null, "INTERNAL_ERROR", "Step execution failed unexpectedly", 0, 0);
            }
            complete(invocation, outcome);
        }
    }

    /** Records one step's outcome under the lease, then advances the run. */
    void complete(StepInvocation invocation, StepOutcome outcome) {
        Lease lease = invocation.lease();
        try {
            inTx(s -> {
                record(invocation, outcome);
                return null;
            });
        } catch (LeaseLostException e) {
            log.warn("lease lost runId={} epoch={}; discarding the result of {}", lease.runId(), lease.epoch(),
                    invocation.step().id());
            metrics.leaseLost();
            return;
        } catch (RuntimeException e) {
            // the step stays RUNNING; recovery (US-5.5) decides from its idempotency whether to send it again
            releaseForRecovery(lease, "recording " + invocation.step().id(), e);
            return;
        }
        metrics.stepFinished(invocation.pipeline(), invocation.step().id(), invocation.step().definition().endpoint(),
                outcome.errorCode() != null ? outcome.errorCode() : outcome.status().name(),
                java.time.Duration.ofMillis(outcome.durationMs()));
        for (StepAttempt attempt : outcome.trail()) {
            metrics.attempt(attempt.record().endpointName(), attempt.record().outcome(), attempt.record().requestBytes(),
                    attempt.record().responseBytes());
        }
        advance(lease);
    }

    private void record(StepInvocation invocation, StepOutcome outcome) {
        leases.lockFenced(invocation.lease());
        java.util.UUID runId = invocation.runId();
        CompiledStep step = invocation.step();
        StepRef ref = StepRef.of(step.id(), invocation.execNo());
        storeAttempts(invocation, outcome);
        if (outcome.status() == StepOutcome.Status.SUCCEEDED) {
            rows.putContext(runId, step.id(), RunContext.stepResult(outcome.httpStatus(), outcome.headers(),
                    outcome.body()));
            long seq = recorder.record(runId, EventType.STEP_SUCCEEDED, ref, details("attempts", outcome.attempts(),
                    "durationMs", outcome.durationMs(), "httpStatus", outcome.httpStatus()), null);
            rows.finishStep(runId, step.id(), StepStatus.SUCCEEDED, outcome.attempts(), outcome.httpStatus(),
                    outcome.durationMs(), null, null, false, seq);
            return;
        }
        if (HttpStepExecutor.DEADLINE_EXCEEDED.equals(outcome.errorCode())) {
            recordDeadlineCut(invocation, outcome, ref);
            return;
        }
        OnFailure policy = step.definition().effectiveOnFailure();
        long seq = recorder.record(runId, EventType.STEP_FAILED, ref, details("code", outcome.errorCode(),
                "message", outcome.errorMessage(), "httpStatus", outcome.httpStatus(), "policy", policy.name()), null);
        FailurePolicyApplier.PolicyOutcome applied = FailurePolicyApplier.apply(step, invocation.context(),
                new FailurePolicyApplier.Failure(outcome.errorCode(), outcome.errorMessage(), outcome.httpStatus()),
                evaluator);
        if (applied instanceof FailurePolicyApplier.FallbackApplied fallback) {
            rows.putContext(runId, step.id(), RunContext.stepResult(null, null, fallback.body()));
            recorder.record(runId, EventType.STEP_FALLBACK_APPLIED, ref, Map.of("errorCode",
                    String.valueOf(outcome.errorCode())), null);
            rows.finishStep(runId, step.id(), StepStatus.SUCCEEDED, outcome.attempts(), outcome.httpStatus(),
                    outcome.durationMs(), outcome.errorCode(), outcome.errorMessage(), true, seq);
            log.info("fallback applied errorCode={}", outcome.errorCode());
            return;
        }
        log.warn("step failed policy={} code={} httpStatus={}", policy, outcome.errorCode(), outcome.httpStatus());
        if (applied instanceof FailurePolicyApplier.StopRun stop) {
            rows.finishStep(runId, step.id(), StepStatus.FAILED, outcome.attempts(), outcome.httpStatus(),
                    outcome.durationMs(), stop.code(), stop.message(), false, seq);
            // README §5.1 rule 7: the stop intent commits with the failure, so no node starts another step
            rows.stop(runId, "FAIL_RUN", STEP_FAILED, stop.message(), step.id());
            return;
        }
        rows.finishStep(runId, step.id(), StepStatus.FAILED, outcome.attempts(), outcome.httpStatus(),
                outcome.durationMs(), outcome.errorCode(), outcome.errorMessage(), false, seq);
    }

    /**
     * The attempt rows and their events, in the outcome's transaction (US-7.2 AC6): {@code TOKEN_REFRESHED} for a
     * repeated OAuth2 call, {@code RESPONSE_RECEIVED} when a response arrived, then the complete attempt row.
     */
    private void storeAttempts(StepInvocation invocation, StepOutcome outcome) {
        if (outcome.trail().isEmpty()) {
            return;
        }
        CaptureSettings settings = captures.forStep(
                pipelines.compiled(invocation.pipeline(), invocation.version()).definition(), invocation.step());
        for (StepAttempt attempt : outcome.trail()) {
            AttemptRecord a = attempt.record();
            StepRef ref = StepRef.attempt(a.stepId(), a.execNo(), a.attemptNo());
            for (String endpoint : attempt.tokenRefreshes()) {
                recorder.record(a.runId(), EventType.TOKEN_REFRESHED, ref, Map.of("endpoint", endpoint), null);
            }
            if (attempt.responseReceived()) {
                recorder.record(a.runId(), EventType.RESPONSE_RECEIVED, ref, details("httpStatus", a.responseStatus(),
                        "durationMs", a.durationMs(), "requestBytes", a.requestBytes(), "responseBytes",
                        a.responseBytes()), null);
            }
            attempts.write(a, settings.capture(), settings.maxBodyBytes(), settings.policy());
        }
    }

    /**
     * US-5.1 AC3, AC4: an attempt cut by the deadline is never a step {@code TIMEOUT} and never re-sent. Not sent, or
     * idempotent: {@code CANCELLED}; sent and not idempotent: {@code UNKNOWN}. Failure policies don't apply; the
     * run stops with {@code DEADLINE}.
     */
    private void recordDeadlineCut(StepInvocation invocation, StepOutcome outcome, StepRef ref) {
        java.util.UUID runId = invocation.runId();
        CompiledStep step = invocation.step();
        boolean sent = !outcome.trail().isEmpty();
        StepStatus status = sent && !step.definition().effectiveIdempotent() ? StepStatus.UNKNOWN
                : StepStatus.CANCELLED;
        long seq = recorder.record(runId, status == StepStatus.UNKNOWN ? EventType.STEP_OUTCOME_UNKNOWN
                : EventType.STEP_FAILED, ref, details("code", outcome.errorCode(), "status", status.name(),
                        "sent", sent), null);
        rows.finishStep(runId, step.id(), status, outcome.attempts(), outcome.httpStatus(), outcome.durationMs(),
                outcome.errorCode(), outcome.errorMessage(), false, seq);
        rows.stop(runId, DEADLINE, RUN_TIMEOUT, "Run deadline reached", null);
    }

    /** The segment timer fired (US-5.1): persist the stop, then let {@code advance} cancel and finalize. */
    void deadlineReached(Lease lease) {
        try {
            boolean stopped = Boolean.TRUE.equals(inTx(s -> {
                leases.lockFenced(lease);
                RunRows.Run run = rows.run(lease.runId()).orElseThrow();
                if (run.status().terminal() || run.stopReason() != null) {
                    return false;
                }
                rows.stop(run.id(), DEADLINE, RUN_TIMEOUT, "Run deadline reached", null);
                return true;
            }));
            if (stopped) {
                List<String> running = rows.steps(lease.runId()).stream()
                        .filter(st -> st.status() == StepStatus.RUNNING).map(RunRows.Step::stepId).toList();
                log.warn("segment deadline reached runId={} inFlight={}", lease.runId(), running);
                advance(lease);
            }
        } catch (LeaseLostException e) {
            metrics.leaseLost();
        }
    }

    private void finish(RunRows.Run run, CompiledPipeline pipeline, RunStatus status, int stepCount) {
        ObjectNode context = context(run);
        RunStatus finalStatus = status;
        try {
            // AC6: evaluated over whatever data exists, also when the run failed
            JsonNode output = RunContext.output(pipeline, context, evaluator);
            rows.putContext(run.id(), "output", output.isMissingNode() ? context.objectNode() : output);
        } catch (ExpressionException e) {
            // AC7: no output; a completed run becomes COMPLETED_WITH_ERRORS, a failed one stays FAILED
            log.warn("output expression failed code={}", e.code());
            rows.recordError(run.id(), OUTPUT_EXPRESSION_ERROR, e.getMessage());
            if (finalStatus == RunStatus.COMPLETED) {
                finalStatus = RunStatus.COMPLETED_WITH_ERRORS;
            }
        }
        EventType event = switch (finalStatus) {
            case COMPLETED -> EventType.RUN_COMPLETED;
            case COMPLETED_WITH_ERRORS -> EventType.RUN_COMPLETED_WITH_ERRORS;
            case TIMED_OUT -> EventType.RUN_TIMED_OUT;
            default -> EventType.RUN_FAILED;
        };
        Map<String, Object> details = new HashMap<>();
        details.put("steps", stepCount);
        if (finalStatus == RunStatus.TIMED_OUT) {
            List<RunRows.Step> cut = rows.steps(run.id()).stream()
                    .filter(st -> HttpStepExecutor.DEADLINE_EXCEEDED.equals(st.errorCode())).toList();
            details.put("aborted", cut.stream().map(RunRows.Step::stepId).toList());
            details.put("unknown", cut.stream().filter(st -> st.status() == StepStatus.UNKNOWN)
                    .map(RunRows.Step::stepId).toList());
            rows.deadlineAt(run.id()).ifPresent(at -> details.put("deadlineAt", at.toString()));
        }
        if (run.startedAt() != null) {
            details.put("durationMs", java.time.Duration.between(run.startedAt(), Instant.now(clock)).toMillis());
        }
        recorder.record(run.id(), event, null, details, null);
        rows.finishRun(run.id(), finalStatus);
        exchanges.finalResult(run.id());
        java.util.UUID runId = run.id();
        RunStatus logged = finalStatus;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deadlines.cancel(runId);
                log.info("run finished runId={} status={}", runId, logged);
                metrics.runFinished(run.pipelineCode(), logged.name(), java.time.Duration.between(
                        run.startedAt() != null ? run.startedAt() : run.createdAt(), Instant.now(clock)));
                notifier.notifyDone(runId);
            }
        });
    }

    /** US-7.6 AC4: corrupt or moved context is never used; the run fails with {@code CONTEXT_DECRYPTION_FAILED}. */
    private void failUnreadable(Lease lease, ContextCipher.DecryptionException e) {
        try {
            inTx(s -> {
                leases.lockFenced(lease);
                RunRows.Run run = rows.run(lease.runId()).orElseThrow();
                if (run.status().terminal()) {
                    return null;
                }
                rows.stop(run.id(), "FAIL_RUN", ContextCipher.DecryptionException.CODE, e.getMessage(), null);
                rows.steps(run.id()).stream().filter(st -> st.status() == StepStatus.PENDING)
                        .forEach(st -> rows.cancelStep(run.id(), st.stepId()));
                recorder.record(run.id(), EventType.RUN_FAILED, null,
                        Map.of("code", ContextCipher.DecryptionException.CODE), null);
                rows.finishRun(run.id(), RunStatus.FAILED);
                exchanges.finalResult(run.id());
                java.util.UUID runId = run.id();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deadlines.cancel(runId);
                        notifier.notifyDone(runId);
                    }
                });
                return null;
            });
        } catch (LeaseLostException lost) {
            metrics.leaseLost();
        }
    }

    /** The step's view: {@code input}, {@code run} and its ancestors' results only (US-4.4, the US-3.2 rule). */
    private static ObjectNode snapshot(ObjectNode context, CompiledPipeline pipeline, CompiledStep step) {
        ObjectNode view = context.deepCopy();
        java.util.Set<String> ancestors = pipeline.ancestors(step.id());
        ((ObjectNode) view.path("steps")).retain(ancestors);
        return view;
    }

    /**
     * One coordinator transaction, run again when SQL Server picks it as a deadlock victim or a lock wait times out.
     * The transaction rolled back whole, so it is safe to repeat; it still starts with the fenced lock.
     */
    private <T> T inTx(org.springframework.transaction.support.TransactionCallback<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(work);
            } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                if (attempt >= LOCK_RETRIES) {
                    throw e;
                }
                log.warn("transaction retried after {} attempt={}", e.getClass().getSimpleName(), attempt);
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** A transaction that keeps failing never strands a run: release it with a wake-up for recovery (US-5.5). */
    private void releaseForRecovery(Lease lease, String what, RuntimeException e) {
        log.error("{} failed runId={}; released for recovery", what, lease.runId(), e);
        try {
            leases.release(lease, true);
        } catch (RuntimeException releaseFailed) {
            log.error("release failed runId={}; the lease expires instead", lease.runId(), releaseFailed);
        }
    }

    private ObjectNode context(RunRows.Run run) {
        Map<String, JsonNode> stored = rows.context(run.id());
        JsonNode input = stored.getOrDefault("input", com.fasterxml.jackson.databind.node.NullNode.getInstance());
        Map<String, JsonNode> results = new LinkedHashMap<>(stored);
        results.remove("input");
        results.remove("output");
        return RunContext.build(run.id(), run.correlationId(), run.pipelineCode(), run.pipelineVersion(), input,
                results);
    }

    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                out.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return out;
    }
}
