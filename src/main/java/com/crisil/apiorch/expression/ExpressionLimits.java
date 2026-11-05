package com.crisil.apiorch.expression;

/**
 * The checks that run before the JSONata parser (ADR-002 D4, D6): length, bracket nesting outside string literals
 * (5000 nested parentheses overflow the parser's stack, H10), and regex literals, which no in-process bound can
 * interrupt. Pure text analysis, shared by the evaluator and by validation.
 */
public final class ExpressionLimits {

    public static final String TOO_LONG = "EXPRESSION_TOO_LONG";
    public static final String TOO_DEEP = "EXPRESSION_TOO_DEEP";
    public static final String REGEX_NOT_ALLOWED = "REGEX_NOT_ALLOWED";

    private ExpressionLimits() {
    }

    /** {@code null} when {@code source} passes, otherwise the violated rule's code. */
    public static String violation(String source, int maxLength, int maxNesting) {
        if (source.length() > maxLength) {
            return TOO_LONG;
        }
        int depth = 0;
        char quote = 0;
        char previous = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                    previous = 'x';
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                if (++depth > maxNesting) {
                    return TOO_DEEP;
                }
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == '/' && startsOperand(previous)) {
                return REGEX_NOT_ALLOWED;
            }
            if (!Character.isWhitespace(c)) {
                previous = c;
            }
        }
        return null;
    }

    /** A {@code /} after these (or at the start) opens a regex literal; anywhere else it is division. */
    private static boolean startsOperand(char previous) {
        return previous == 0 || "(,[:{=!<>+-*&?;~|%".indexOf(previous) >= 0;
    }
}
