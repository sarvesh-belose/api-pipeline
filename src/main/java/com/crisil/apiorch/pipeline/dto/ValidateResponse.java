package com.crisil.apiorch.pipeline.dto;

import java.util.List;

import com.crisil.apiorch.definition.DefinitionIssue;

/**
 * {@code POST /api/v1/admin/pipelines/{code}/validate} (US-3.2). {@code executionPlan} is a list of levels: step
 * ids, or with {@code ?detail=true} objects {@code {id, postCompletion}}.
 */
public record ValidateResponse(
        boolean valid,
        String checked,
        List<List<Object>> executionPlan,
        List<String> planDescription,
        List<DefinitionIssue> errors,
        List<DefinitionIssue> warnings) {

    public record PlanEntry(String id, boolean postCompletion) {
    }
}
