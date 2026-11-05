package com.crisil.apiorch.definition;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crisil.apiorch.expression.CompiledExpression;

/**
 * A published version ready to execute (US-3.3, US-4.3): built once per {@code (code, version)} and cached, so a
 * run never re-parses its definition. Immutable and shared between runs.
 *
 * @param steps      in list order
 * @param dependents for each step id, the steps that depend on it directly
 * @param order      a topological order of the step ids, ties broken by list order
 */
public record CompiledPipeline(
        String code,
        int version,
        int dslVersion,
        PipelineDefinition definition,
        List<CompiledStep> steps,
        Map<String, CompiledStep> byId,
        Map<String, Set<String>> dependents,
        List<String> order,
        CompiledExpression inputValidWhen,
        CompiledExpression output) {

    public CompiledStep step(String id) {
        CompiledStep step = byId.get(id);
        if (step == null) {
            throw new IllegalArgumentException("No step '" + id + "' in " + code + " v" + version);
        }
        return step;
    }

    /** Steps with no dependencies. */
    public List<CompiledStep> roots() {
        return steps.stream().filter(s -> s.dependsOn().isEmpty()).toList();
    }
}
