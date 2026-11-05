package com.crisil.apiorch.definition;

import java.util.List;
import java.util.Set;

/**
 * The verdict on a definition (US-3.2): errors block validate and publish, warnings never do. The execution plan
 * is the list of topological levels, each ordered by list position; it is empty when the graph itself is invalid.
 * {@code postCompletion} names steps that run only after a non-blocking async step's callback (US-6.3).
 */
public record ValidationReport(
        boolean valid,
        List<List<String>> executionPlan,
        Set<String> postCompletion,
        List<String> planDescription,
        List<DefinitionIssue> errors,
        List<DefinitionIssue> warnings) {

    public static ValidationReport invalid(List<DefinitionIssue> errors) {
        return new ValidationReport(false, List.of(), Set.of(), List.of(), List.copyOf(errors), List.of());
    }
}
