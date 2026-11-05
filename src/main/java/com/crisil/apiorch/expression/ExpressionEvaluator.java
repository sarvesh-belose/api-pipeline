package com.crisil.apiorch.expression;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The expression engine the rest of apiorch depends on (US-4.3, README §8). The implementation chosen by gate G2
 * is {@code GuardedJsonataEvaluator} over JSONata 0.9.10 (ADR-002 D1–D7); this interface keeps it swappable
 * without touching the engine.
 *
 * <ul>
 *   <li>{@link #compile} runs once per expression per published version (US-3.2, US-3.3). It enforces the
 *       pre-compile limits ({@code apiorch.expression.max-length}, {@code max-nesting}, no regex literals) and
 *       throws {@link ExpressionException} with {@link ExpressionException.Kind#SYNTAX} or
 *       {@link ExpressionException.Kind#NOT_ALLOWED}.</li>
 *   <li>{@link #evaluate} is thread-safe and bounded: wall-clock {@code apiorch.expression.timeout-ms} and depth
 *       {@code apiorch.expression.max-depth}. An over-budget evaluation throws
 *       {@link ExpressionException.Kind#BUDGET_EXCEEDED}; any other evaluation failure is
 *       {@link ExpressionException.Kind#EVALUATION}. Neither is retried (US-4.3 AC7).</li>
 *   <li>An undefined result is {@link com.fasterxml.jackson.databind.node.MissingNode}, which callers treat as
 *       "omit" (query parameters, headers) rather than JSON {@code null}.</li>
 * </ul>
 */
public interface ExpressionEvaluator {

    CompiledExpression compile(String source);

    /**
     * Evaluates over the read-only run context
     * {@code {input, steps.<id>.{status, headers, body, ack}, run, callback?, error?}} (README §8).
     */
    JsonNode evaluate(CompiledExpression expression, JsonNode context);
}
