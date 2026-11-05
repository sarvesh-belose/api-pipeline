package com.crisil.apiorch.expression;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * JSONata 0.9.10 with the ADR-002 guards (D1–D6):
 * <ul>
 *   <li>D1: a pool of compiled instances per expression; an evaluation borrows one, so instances, which write
 *       shared fields while evaluating, are never used by two threads at once;</li>
 *   <li>D2: {@code setRuntimeBounds(timeoutMs, maxDepth)} on a fresh frame for every evaluation (wall-clock);</li>
 *   <li>D3: unbounded built-ins are overridden on every pooled instance ({@code $pad} capped at width 10,000);</li>
 *   <li>D4, D6: {@link ExpressionLimits} before the parser; a parser {@code StackOverflowError} is caught too;</li>
 *   <li>D5: any failure whose cause chain holds a {@code JException} is an {@link ExpressionException}.</li>
 * </ul>
 * Inputs and results are Jackson 2 trees; an undefined result is {@link MissingNode}. The library returns a
 * top-level {@code null} the same way as undefined, so it is also {@code MissingNode}; nested nulls are kept.
 */
public final class GuardedJsonataEvaluator implements ExpressionEvaluator {

    static final int PAD_LIMIT = 10_000;

    private final long timeoutMs;
    private final int maxDepth;
    private final int maxLength;
    private final int maxNesting;
    private final ObjectMapper json;

    public GuardedJsonataEvaluator(long timeoutMs, int maxDepth, int maxLength, int maxNesting, ObjectMapper json) {
        this.timeoutMs = timeoutMs;
        this.maxDepth = maxDepth;
        this.maxLength = maxLength;
        this.maxNesting = maxNesting;
        this.json = json;
    }

    private final class Compiled implements CompiledExpression {
        private final String source;
        private final ConcurrentLinkedQueue<Jsonata> pool = new ConcurrentLinkedQueue<>();

        Compiled(String source, Jsonata first) {
            this.source = source;
            pool.offer(first);
        }

        @Override
        public String source() {
            return source;
        }

        Jsonata borrow() {
            Jsonata instance = pool.poll();
            return instance != null ? instance : parse(source);
        }

        void giveBack(Jsonata instance) {
            pool.offer(instance);
        }
    }

    @Override
    public CompiledExpression compile(String source) {
        String violation = ExpressionLimits.violation(source, maxLength, maxNesting);
        if (violation != null) {
            throw new ExpressionException(ExpressionException.Kind.NOT_ALLOWED, violation, -1, switch (violation) {
                case ExpressionLimits.TOO_LONG -> "Expression is longer than " + maxLength + " characters";
                case ExpressionLimits.TOO_DEEP -> "Expression nests brackets deeper than " + maxNesting;
                default -> "Regex literals are not allowed in R1 expressions; use the string forms of $contains, "
                        + "$split or $substring";
            }, null);
        }
        return new Compiled(source, parse(source));
    }

    private Jsonata parse(String source) {
        Jsonata instance;
        try {
            instance = Jsonata.jsonata(source);
        } catch (StackOverflowError e) {
            throw new ExpressionException(ExpressionException.Kind.NOT_ALLOWED, ExpressionLimits.TOO_DEEP, -1,
                    "Expression is too deeply nested to parse", null);
        } catch (RuntimeException e) {
            JException j = jException(e);
            throw new ExpressionException(ExpressionException.Kind.SYNTAX, j != null ? j.getError() : null,
                    j != null ? j.getLocation() : -1, j != null ? j.getMessage() : "Expression does not parse", e);
        }
        instance.registerFunction("pad", new Jsonata.JFunction((input, args) -> pad((String) args.get(0),
                (Number) args.get(1), args.size() > 2 ? (String) args.get(2) : null), "<s-ns?:s>"));
        return instance;
    }

    /** {@code $pad} with the D3 cap; otherwise the JSONata semantics (negative width pads on the left). */
    static String pad(String s, Number width, String chars) {
        if (s == null || width == null) {
            return s;
        }
        long w = width.longValue();
        if (Math.abs(w) > PAD_LIMIT) {
            throw new JException("D3999: $pad width above the guard limit of " + PAD_LIMIT, -1);
        }
        String fill = chars == null || chars.isEmpty() ? " " : chars;
        int missing = (int) Math.abs(w) - s.codePointCount(0, s.length());
        if (missing <= 0) {
            return s;
        }
        StringBuilder padding = new StringBuilder();
        while (padding.length() < missing) {
            padding.append(fill);
        }
        padding.setLength(missing);
        return w < 0 ? padding + s : s + padding;
    }

    @Override
    public JsonNode evaluate(CompiledExpression expression, JsonNode context) {
        Compiled compiled = (Compiled) expression;
        Object input = context == null || context.isMissingNode() ? null : json.convertValue(context, Object.class);
        Jsonata instance = compiled.borrow();
        try {
            Jsonata.Frame frame = instance.createFrame();
            frame.setRuntimeBounds(timeoutMs, maxDepth);
            Object result = instance.evaluate(input, frame);
            if (result == null) {
                return MissingNode.getInstance();
            }
            if (result == Jsonata.NULL_VALUE) {
                return NullNode.getInstance();
            }
            return json.valueToTree(result);
        } catch (StackOverflowError e) {
            throw new ExpressionException(ExpressionException.Kind.BUDGET_EXCEEDED, null, -1,
                    "Expression recursed too deeply", null);
        } catch (RuntimeException e) {
            JException j = jException(e);
            if (j == null) {
                throw new ExpressionException(ExpressionException.Kind.EVALUATION, null, -1,
                        "Expression failed: " + e.getClass().getSimpleName(), e);
            }
            throw new ExpressionException(isBudget(j) ? ExpressionException.Kind.BUDGET_EXCEEDED
                    : ExpressionException.Kind.EVALUATION, j.getError(), j.getLocation(), j.getMessage(), e);
        } finally {
            compiled.giveBack(instance);
        }
    }

    static boolean isBudget(JException j) {
        String message = String.valueOf(j.getMessage());
        return message.contains("timeout") || message.contains("Stack overflow") || message.startsWith("D3999");
    }

    static JException jException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof JException j) {
                return j;
            }
        }
        return null;
    }
}
