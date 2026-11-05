package com.crisil.apiorch.expression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds which steps an expression reads (US-3.2): {@code steps.<id>} and {@code steps['<id>']}. Deny-by-default:
 * any other occurrence of the token {@code steps} outside a string literal ({@code $lookup(steps, $k)},
 * {@code steps.*}, {@code steps[$x]}, a construct this scanner was never taught) is reported as dynamic, because it
 * cannot be checked against the execution order. That keeps the reorder guarantee sound as the DSL grows.
 */
public final class ReferenceScanner {

    private static final Pattern DOTTED = Pattern.compile("(?<![\\w$.])steps\\.([a-z][a-z0-9_]*)\\b");
    private static final Pattern BRACKET = Pattern.compile("(?<![\\w$.])steps\\[\\s*['\"]([a-z][a-z0-9_]*)['\"]\\s*\\]");
    private static final Pattern TOKEN = Pattern.compile("(?<![\\w$.])steps\\b");

    /** The step ids read, and whether any access was dynamic. */
    public record References(Set<String> steps, boolean dynamic) {
    }

    private ReferenceScanner() {
    }

    public static References scan(String expression) {
        if (expression == null) {
            return new References(Set.of(), false);
        }
        Set<String> steps = new LinkedHashSet<>();
        char[] masked = expression.toCharArray();
        for (Pattern p : List.of(BRACKET, DOTTED)) {
            Matcher m = p.matcher(new String(masked));
            List<int[]> consumed = new ArrayList<>();
            while (m.find()) {
                steps.add(m.group(1));
                consumed.add(new int[] {m.start(), m.end()});
            }
            for (int[] range : consumed) {
                for (int i = range[0]; i < range[1]; i++) {
                    masked[i] = ' ';
                }
            }
        }
        String rest = withoutStringLiterals(new String(masked));
        boolean dynamic = TOKEN.matcher(rest).find();
        return new References(steps, dynamic);
    }

    /** Blanks the contents of quoted strings, so {@code 'steps'} as text is not a reference. */
    static String withoutStringLiterals(String text) {
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < text.length()) {
                    out.append("  ");
                    i++;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                    out.append(c);
                } else {
                    out.append(' ');
                }
            } else {
                if (c == '\'' || c == '"') {
                    quote = c;
                }
                out.append(c);
            }
        }
        return out.toString();
    }
}
