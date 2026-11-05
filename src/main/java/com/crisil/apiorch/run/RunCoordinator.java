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
import com.crisil.apiorch.definition.PipelineDefinition;
import com.crisil.apiorch.definition.PipelineDefinition.Capture;
import com.crisil.apiorch.definition.StepDefinition.OnFailure;
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
import com.crisil.apiorch.observability.MaskingPolicy;
import com.crisil.apiorch.http.EndpointClientRegistry;
import com.crisil.apiorch.observability.EventType;
import com.crisil.apiorch.observability.MdcSupport;
import com.crisil.apiorch.persistence.ContextCipher;
import com.crisil.apiorch.persistence.RunRows;
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
    private final EndpointClientRegistry endpoints;
    private final ApiorchProperties.Logging logging;
    private final ApiorchMetrics metrics;
    private final Clock clock;
    private final long defaultRunTimeoutMs;

    public RunCoordinator(TransactionTemplate tx, RunRows rows, RunLeaseService leases, ActivityRecorder recorder,
            PublishedPipelines pipelines, StepExecutor executor,
            @Qualifier(ExecutorConfig.STEP_IO) ThreadPoolTaskExecutor stepIo, RunCompletionNotifier notifier,
            ExpressionEvaluator evaluator, AttemptWriter attempts, EndpointClientRegistry endpoints,
            ApiorchMetrics metrics, Clock clock, ApiorchProperties properties) {
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
        this.endpoints = endpoints;
        this.logging = properties.logging();
        this.metrics = metrics;
        this.clock = clock;
        this.defaultRunTimeoutMs = properties.run().defaultRunTimeoutMs();
    }

    /** {@code ACCEPTED → RUNNING} with {@code RUN_STARTED}, then the first {@link #advance}. */
    public void start(Lease lease) {
        tx.executeWithoutResult(s -> {
            leases.lockFenced(lease);
            RunRows.Run run = rows.run(lease.runId()).orElseThrow();
            CompiledPipeline pipeline = pipelines.compiled(run.pipelineCode(), run.pipelineVersion());
            long timeout = pipeline.definition().runTimeoutMs() != null ? pipeline.definition().runTimeoutMs()
                    : defaultRunTimeoutMs;
            rows.markRunning(lease.runId(), timeout);
            recorder.record(lease.runId(), EventType.RUN_STARTED, null, Map.of("runTimeoutMs", timeout), null);
        });
        advance(lease);
    }

    /** Schedules every ready step, or finalizes the run at quiescence. Safe to call concurrently. */
    public void advance(Lease lease) {
        List<StepInvocation> scheduled;
        try {
            scheduled = tx.execute(s -> schedule(lease));
        } catch (ContextCipher.DecryptionException e) {
            log.error("run context failed to decrypt runId={}; failing the run", lease.runId(), e);
            failUnreadable(lease, e);
            return;
        } catch (LeaseLostException e) {
            log.warn("lease lost runId={} epoch={}", lease.runId(), lease.epoch());
            metrics.leaseLost();
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
        RunDecision.Next next = RunDecision.decide(pipeline, states);
        for (RunDecision.Skip skip : next.skip()) {
            if (skip.reason().equals("RUN_STOPPED")) {
                rows.cancelStep(run.id(), skip.stepId());
            } else {
                rows.skipStep(run.id(), skip.stepId(), skip.reason());
            }
            recorder.record(run.id(), EventType.STEP_SKIPPED, StepRef.of(skip.stepId(), 1),
                    Map.of("reason", skip.reason()), null);
        }
        if (next.quiescent()) {
            boolean degraded = steps.stream().anyMatch(RunRows.Step::fallbackApplied);
            RunStatus status = next.finalStatus() == RunStatus.COMPLETED && degraded
                    ? RunStatus.COMPLETED_WITH_ERRORS : next.finalStatus();
            finish(run, pipeline, status, steps.size());
            return List.of();
        }
        if (next.ready().isEmpty()) {
            return List.of();
        }
        ObjectNode context = context(run);
        List<StepInvocation> out = new ArrayList<>();
        for (CompiledStep step : next.ready()) {
            rows.markStepRunning(run.id(), step.id());
            recorder.record(run.id(), EventType.STEP_SCHEDULED, StepRef.of(step.id(), 1), Map.of("stepId", step.id(),
                    "execNo", 1, "endpoint", step.definition().endpoint(), "mode", step.definition().effectiveMode().name()),
                    null);
            out.add(new StepInvocation(lease, run.pipelineCode(), run.pipelineVersion(), run.correlationId(), step, 1,
                    context, null));
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
            tx.executeWithoutResult(s -> record(invocation, outcome));
        } catch (LeaseLostException e) {
            log.warn("lease lost runId={} epoch={}; discarding the result of {}", lease.runId(), lease.epoch(),
                    invocation.step().id());
            metrics.leaseLost();
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
        OnFailure policy = step.definition().effectiveOnFailure();
        long seq = recorder.record(runId, EventType.STEP_FAILED, ref, details("code", outcome.errorCode(),
                "message", outcome.errorMessage(), "httpStatus", outcome.httpStatus(), "policy", policy.name()), null);
        if (policy == OnFailure.FALLBACK && step.fallback() != null && applyFallback(invocation, outcome)) {
            recorder.record(runId, EventType.STEP_FALLBACK_APPLIED, ref, Map.of("errorCode",
                    String.valueOf(outcome.errorCode())), null);
            rows.finishStep(runId, step.id(), StepStatus.SUCCEEDED, outcome.attempts(), outcome.httpStatus(),
                    outcome.durationMs(), outcome.errorCode(), outcome.errorMessage(), true, seq);
            return;
        }
        rows.finishStep(runId, step.id(), StepStatus.FAILED, outcome.attempts(), outcome.httpStatus(),
                outcome.durationMs(), outcome.errorCode(), outcome.errorMessage(), false, seq);
        if (policy != OnFailure.CONTINUE) {
            rows.stop(runId, "FAIL_RUN", outcome.errorCode(), outcome.errorMessage(), step.id());
        }
    }

    /**
     * The attempt rows and their events, in the outcome's transaction (US-7.2 AC6): {@code TOKEN_REFRESHED} for a
     * repeated OAuth2 call, {@code RESPONSE_RECEIVED} when a response arrived, then the complete attempt row.
     */
    private void storeAttempts(StepInvocation invocation, StepOutcome outcome) {
        if (outcome.trail().isEmpty()) {
            return;
        }
        PipelineDefinition definition = pipelines.compiled(invocation.pipeline(), invocation.version()).definition();
        PipelineDefinition.LoggingSpec stepLogging = invocation.step().definition().logging();
        PipelineDefinition.LoggingSpec pipelineLogging = definition.logging();
        Capture capture = stepLogging != null && stepLogging.capture() != null ? stepLogging.capture()
                : pipelineLogging != null && pipelineLogging.capture() != null ? pipelineLogging.capture()
                : logging.defaultCapture();
        int maxBody = stepLogging != null && stepLogging.maxBodyBytes() != null ? stepLogging.maxBodyBytes()
                : pipelineLogging != null && pipelineLogging.maxBodyBytes() != null ? pipelineLogging.maxBodyBytes()
                : logging.defaultMaxBodyBytes();
        MaskingPolicy policy = maskingPolicy(invocation.step(), pipelineLogging, stepLogging);
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
            attempts.write(a, capture, maxBody, policy);
        }
    }

    /** The global lists, the pipeline's and step's {@code maskKeys}, and the endpoint's API key header (US-7.3). */
    private MaskingPolicy maskingPolicy(CompiledStep step, PipelineDefinition.LoggingSpec pipelineLogging,
            PipelineDefinition.LoggingSpec stepLogging) {
        List<String> headers = new ArrayList<>(logging.alwaysMaskHeaders());
        endpoints.get(step.definition().endpoint()).map(r -> r.config().auth())
                .filter(auth -> auth != null && auth.headerName() != null).ifPresent(auth -> headers.add(auth.headerName()));
        List<String> keys = new ArrayList<>(logging.alwaysMaskKeys());
        if (pipelineLogging != null && pipelineLogging.maskKeys() != null) {
            keys.addAll(pipelineLogging.maskKeys());
        }
        if (stepLogging != null && stepLogging.maskKeys() != null) {
            keys.addAll(stepLogging.maskKeys());
        }
        return MaskingPolicy.of(headers, keys);
    }

    /** {@code fallback} over the context plus {@code error}; its result becomes {@code steps.<id>.body} (US-4.6). */
    private boolean applyFallback(StepInvocation invocation, StepOutcome outcome) {
        ObjectNode ctx = invocation.context().deepCopy();
        ObjectNode error = ctx.putObject("error");
        error.put("code", outcome.errorCode()).put("message", outcome.errorMessage());
        if (outcome.httpStatus() != null) {
            error.put("httpStatus", outcome.httpStatus());
        }
        try {
            JsonNode body = evaluator.evaluate(invocation.step().fallback(), ctx);
            rows.putContext(invocation.runId(), invocation.step().id(), RunContext.stepResult(null, null, body));
            return true;
        } catch (ExpressionException e) {
            log.warn("fallback expression failed code={}", e.code());
            return false;
        }
    }

    private void finish(RunRows.Run run, CompiledPipeline pipeline, RunStatus status, int stepCount) {
        ObjectNode context = context(run);
        RunStatus finalStatus = status;
        try {
            JsonNode output = RunContext.output(pipeline, context, evaluator);
            rows.putContext(run.id(), "output", output.isMissingNode() ? context.objectNode() : output);
        } catch (ExpressionException e) {
            rows.stop(run.id(), "FAIL_RUN", "OUTPUT_ERROR", e.getMessage(), null);
            finalStatus = RunStatus.FAILED;
        }
        EventType event = switch (finalStatus) {
            case COMPLETED -> EventType.RUN_COMPLETED;
            case COMPLETED_WITH_ERRORS -> EventType.RUN_COMPLETED_WITH_ERRORS;
            default -> EventType.RUN_FAILED;
        };
        Map<String, Object> details = new HashMap<>();
        details.put("steps", stepCount);
        recorder.record(run.id(), event, null, details, null);
        rows.finishRun(run.id(), finalStatus);
        java.util.UUID runId = run.id();
        RunStatus logged = finalStatus;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
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
            tx.executeWithoutResult(s -> {
                leases.lockFenced(lease);
                RunRows.Run run = rows.run(lease.runId()).orElseThrow();
                if (run.status().terminal()) {
                    return;
                }
                rows.stop(run.id(), "FAIL_RUN", ContextCipher.DecryptionException.CODE, e.getMessage(), null);
                rows.steps(run.id()).stream().filter(st -> st.status() == StepStatus.PENDING)
                        .forEach(st -> rows.cancelStep(run.id(), st.stepId()));
                recorder.record(run.id(), EventType.RUN_FAILED, null,
                        Map.of("code", ContextCipher.DecryptionException.CODE), null);
                rows.finishRun(run.id(), RunStatus.FAILED);
                java.util.UUID runId = run.id();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        notifier.notifyDone(runId);
                    }
                });
            });
        } catch (LeaseLostException lost) {
            metrics.leaseLost();
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
