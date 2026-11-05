package com.crisil.apiorch.expression;

/**
 * A failed compile or evaluation (ADR-002 D5). The engine maps it to step error {@code EXPRESSION_ERROR} with
 * {@link #code()} (the JSONata code, for example {@code S0201}) and {@link #position()} when known.
 */
public class ExpressionException extends RuntimeException {

    public enum Kind {
        /** The text does not parse. */
        SYNTAX,
        /** A pre-compile rule refuses it: too long, too deeply nested, or a regex literal (ADR-002 D4, D6). */
        NOT_ALLOWED,
        /** Evaluation failed, for example a type error. */
        EVALUATION,
        /** The wall-clock or depth bound aborted it (ADR-002 D2); counted in {@code apiorch.expression.budget_exceeded}. */
        BUDGET_EXCEEDED
    }

    private final Kind kind;
    private final String code;
    private final int position;

    public ExpressionException(Kind kind, String code, int position, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
        this.position = position;
    }

    public Kind kind() {
        return kind;
    }

    /** The engine's error code, or {@code null}. */
    public String code() {
        return code;
    }

    /** The character position in the source, or {@code -1} when unknown. */
    public int position() {
        return position;
    }
}
