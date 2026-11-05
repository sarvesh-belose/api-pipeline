package com.crisil.apiorch.run;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.engine.StepStatus;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.run.dto.RunResource;

/** Builds the run resource from {@code pipeline_run}, {@code step_execution} and the stored output (US-4.1). */
@Component
public class RunResourceAssembler {

    private final RunRows rows;
    private final PublishedPipelines pipelines;

    public RunResourceAssembler(RunRows rows, PublishedPipelines pipelines) {
        this.rows = rows;
        this.pipelines = pipelines;
    }

    public RunResource assemble(UUID runId) {
        return assemble(rows.run(runId).orElseThrow(() -> notFound(runId)));
    }

    /** The resource for a freshly accepted async run, without reading it back (US-4.2 AC1). */
    public static RunResource accepted(UUID runId, String code, int version) {
        return new RunResource(runId, code, version, "ACCEPTED", "ASYNC", null, null, null, null, null, null, null,
                false, false, null, null, null, links(runId));
    }

    static ApiProblemException notFound(Object runId) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "run-not-found", "Run " + runId + " does not exist.");
    }

    private static Map<String, String> links(UUID runId) {
        return Map.of("self", "/api/v1/runs/" + runId, "timeline", "/api/v1/runs/" + runId + "/timeline");
    }

    RunResource assemble(RunRows.Run run) {
        UUID runId = run.id();
        CompiledPipeline pipeline = pipelines.compiled(run.pipelineCode(), run.pipelineVersion());
        List<RunRows.Step> rowsInListOrder = new ArrayList<>(rows.steps(runId));
        rowsInListOrder.sort(Comparator.comparingInt(s -> pipeline.step(s.stepId()).index()));
        List<RunResource.Step> steps = new ArrayList<>();
        List<RunResource.Error> errors = new ArrayList<>();
        for (RunRows.Step s : rowsInListOrder) {
            steps.add(new RunResource.Step(s.stepId(), s.status().name(), s.attempts(), s.httpStatus(), s.durationMs(),
                    s.fallbackApplied(), s.skipReason()));
            if (s.status() == StepStatus.FAILED || s.fallbackApplied()) {
                errors.add(new RunResource.Error(s.stepId(), s.errorCode(), s.errorMessage(), s.httpStatus()));
            }
        }
        if (run.errorCode() != null && errors.stream().noneMatch(e -> run.errorCode().equals(e.code()))) {
            errors.add(new RunResource.Error(run.errorStepId(), run.errorCode(), run.errorMessage(), null));
        }
        JsonNode output = null;
        if (run.status().doneForCaller()) {
            try {
                output = rows.context(runId).get("output");
            } catch (com.crisil.apiorch.persistence.ContextCipher.DecryptionException e) {
                // logged where the run failed; the resource is still served, without output (US-7.6 AC4)
                output = null;
            }
        }
        Long durationMs = run.startedAt() != null && run.endedAt() != null
                ? Duration.between(run.startedAt(), run.endedAt()).toMillis() : null;
        return new RunResource(run.id(), run.pipelineCode(), run.pipelineVersion(), run.status().name(), run.mode(),
                run.correlationId(), run.createdAt(), run.startedAt(), run.completedAt(), run.endedAt(), durationMs,
                output, run.status().doneForCaller(), false, List.of(), errors, steps,
                links(runId));
    }
}
