package com.crisil.apiorch.definition;

import java.util.ArrayList;
import java.util.List;

/**
 * A step {@code path} split into segments, each a sequence of literal text and {@code {expression}} parts
 * (README §8). Braces inside an expression (a JSONata object literal) are matched, so {@code /a/{ {'x': 1}.x }}
 * is one expression. Each segment is URL-encoded as a whole at request time, and an empty, {@code .} or {@code ..}
 * result is rejected (US-4.3 AC4).
 */
public record PathTemplate(String source, List<List<Part>> segments) {

    /** Literal text, or an expression source when {@code expression} is true. */
    public record Part(String text, boolean expression) {
    }

    public static PathTemplate parse(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        List<List<Part>> segments = new ArrayList<>();
        List<Part> current = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '{') {
                int end = matchingBrace(path, i);
                if (literal.length() > 0) {
                    current.add(new Part(literal.toString(), false));
                    literal.setLength(0);
                }
                String expr = path.substring(i + 1, end).strip();
                if (expr.isEmpty()) {
                    throw new IllegalArgumentException("empty {} in path at " + i);
                }
                current.add(new Part(expr, true));
                i = end + 1;
            } else if (c == '}') {
                throw new IllegalArgumentException("unmatched } in path at " + i);
            } else if (c == '/') {
                close(segments, current, literal);
                current = new ArrayList<>();
                i++;
            } else {
                literal.append(c);
                i++;
            }
        }
        close(segments, current, literal);
        return new PathTemplate(path, List.copyOf(segments));
    }

    private static void close(List<List<Part>> segments, List<Part> current, StringBuilder literal) {
        if (literal.length() > 0) {
            current.add(new Part(literal.toString(), false));
            literal.setLength(0);
        }
        if (!current.isEmpty()) {
            segments.add(List.copyOf(current));
        }
    }

    private static int matchingBrace(String path, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("unclosed { in path at " + open);
    }

    /** Every expression source in the path, in order. */
    public List<String> expressions() {
        return segments.stream().flatMap(List::stream).filter(Part::expression).map(Part::text).toList();
    }
}
