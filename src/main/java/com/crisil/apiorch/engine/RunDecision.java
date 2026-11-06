package com.crisil.apiorch.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.CompiledStep;

/**
 * What a run should do next, decided from its compiled pipeline and step states alone (US-4.1, US-4.3, US-4.4,
 * US-4.6). Pure, so the coordinator, the dry run (US-3.5) and tests share one rule set:
 * <ul>
 *   <li>a pending step is <b>ready</b> when every dependency succeeded and the run is not stopping;</li>
 *   <li>a pending step is <b>skipped</b> when a dependency did not succeed ({@code DEPENDENCY_FAILED}), or
 *       when the run is stopping and the step never started ({@code RUN_STOPPED}, stored as {@code CANCELLED});</li>
 *   <li>the run is <b>stopping</b> when its persisted {@code stop_reason} is set, or a {@code FAIL_RUN} step
 *       failed (README §5.1 rule 7);</li>
 *   <li>at <b>quiescence</b> (nothing pending or running) the run ends: {@code FAILED} if it was stopping, {@code COMPLETED_WITH_ERRORS} if any other step failed or was skipped, else {@code COMPLETED}.</li>
 * </ul>
 */
public final class RunDecision {

    public record Skip(String stepId, String reason) {
    }

    /** {@code finalStatus} is set only at quiescence. */
    public record Next(List<CompiledStep> ready, List<Skip> skip, RunStatus finalStatus) {

        public boolean quiescent() {
            return finalStatus != null;
        }
    }

    private RunDecision() {
    }

    public static Next decide(CompiledPipeline pipeline, Map<String, StepState> states) {
        return decide(pipeline, states, false);
    }

    /** {@code stopRequested}: the run's {@code stop_reason} is set, so nothing new starts on any node (US-4.6 AC2). */
    public static Next decide(CompiledPipeline pipeline, Map<String, StepState> states, boolean stopRequested) {
        boolean stopping = stopRequested || states.values().stream().anyMatch(StepState::failedWithStop);
        List<CompiledStep> ready = new ArrayList<>();
        List<Skip> skip = new ArrayList<>();
        Map<String, StepStatus> effective = new java.util.HashMap<>();
        states.forEach((id, s) -> effective.put(id, s.status()));
        // walk in topological order so a skip propagates to dependents in the same decision
        for (String id : pipeline.order()) {
            if (effective.get(id) != StepStatus.PENDING) {
                continue;
            }
            CompiledStep step = pipeline.step(id);
            boolean blocked = false;
            boolean waiting = false;
            for (String dep : step.dependsOn()) {
                StepStatus depStatus = effective.get(dep);
                if (depStatus == StepStatus.SUCCEEDED) {
                    continue;
                }
                if (depStatus.finished()) {
                    blocked = true;
                } else {
                    waiting = true;
                }
            }
            if (stopping) {
                skip.add(new Skip(id, "RUN_STOPPED"));
                effective.put(id, StepStatus.SKIPPED);
            } else if (blocked) {
                skip.add(new Skip(id, "DEPENDENCY_FAILED"));
                effective.put(id, StepStatus.SKIPPED);
            } else if (!waiting) {
                ready.add(step);
                effective.put(id, StepStatus.RUNNING);
            }
        }
        boolean active = effective.values().stream().anyMatch(s -> !s.finished());
        RunStatus finalStatus = null;
        if (!active) {
            boolean anyNotSucceeded = effective.values().stream().anyMatch(s -> s != StepStatus.SUCCEEDED);
            finalStatus = stopping ? RunStatus.FAILED : anyNotSucceeded ? RunStatus.COMPLETED_WITH_ERRORS : RunStatus.COMPLETED;
        }
        return new Next(List.copyOf(ready), List.copyOf(skip), finalStatus);
    }
}
