package com.crisil.apiorch.definition;

import java.util.List;
import java.util.Map;

import com.crisil.apiorch.expression.CompiledExpression;

/**
 * One step of a {@link CompiledPipeline}: its definition, its effective dependencies and every expression
 * compiled once (US-3.3, US-4.3). Expressions that were not declared are {@code null}; path parts are compiled in
 * {@link #pathExpressions()} keyed by their source text.
 */
public record CompiledStep(
        int index,
        StepDefinition definition,
        List<String> dependsOn,
        PathTemplate path,
        Map<String, CompiledExpression> pathExpressions,
        Map<String, CompiledExpression> query,
        Map<String, CompiledExpression> headers,
        CompiledExpression body,
        CompiledExpression when,
        CompiledExpression successWhen,
        CompiledExpression fallback,
        CompiledExpression callbackSuccessWhen,
        Compensation compensation) {

    public String id() {
        return definition.id();
    }

    /** The compiled compensation call (US-5.6); its endpoint defaults to the step's own. */
    public record Compensation(
            String endpoint,
            StepDefinition.Method method,
            PathTemplate path,
            Map<String, CompiledExpression> pathExpressions,
            Map<String, CompiledExpression> query,
            Map<String, CompiledExpression> headers,
            CompiledExpression body) {
    }
}
