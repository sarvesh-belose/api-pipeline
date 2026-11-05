package com.crisil.apiorch.expression;

/**
 * An expression compiled by an {@link ExpressionEvaluator}. Opaque to callers; only the evaluator that produced it
 * can evaluate it. Implementations are immutable and safe to share between threads (ADR-002 D1 pools the
 * underlying JSONata instances behind this handle).
 */
public interface CompiledExpression {

    /** The source text, for diagnostics. Never logged by the engine (US-1.3 logging rule). */
    String source();
}
