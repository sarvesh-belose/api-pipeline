package com.crisil.apiorch.step;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.definition.PathTemplate;
import com.crisil.apiorch.expression.CompiledExpression;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;
import com.crisil.apiorch.http.UriBuilder;

/**
 * Builds one step's HTTP request from its compiled expressions and the context snapshot (US-4.3). Pure: no I/O,
 * so the dry run (US-3.5) reuses it. Each path segment is URL-encoded as a whole, and an empty, {@code .} or
 * {@code ..} segment is refused, so a response value can never move the request off the endpoint's base path
 * (AC4). Query parameters and headers whose expression yields nothing are omitted. Step headers can't replace the
 * headers apiorch owns (AC5).
 */
public final class RequestBuilder {

    /** The request to send; {@code body} is {@code null} when the step declares none. */
    public record StepRequest(String method, URI uri, Map<String, String> headers, JsonNode body, byte[] bodyBytes) {
    }

    /** A step whose request can't be built: always {@code EXPRESSION_ERROR}, never retried (AC7). */
    public static final class BuildException extends RuntimeException {

        private final String field;

        BuildException(String field, String message, Throwable cause) {
            super(field + ": " + message, cause);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private RequestBuilder() {
    }

    /**
     * @param fixed the headers apiorch sets (correlation, run and step identity, idempotency key, accept); step
     *              headers with the same name, compared case-insensitively, are dropped
     */
    public static StepRequest build(CompiledStep step, JsonNode context, ExpressionEvaluator evaluator, String baseUrl,
            Map<String, String> fixed) {
        List<String> segments = new ArrayList<>();
        for (List<PathTemplate.Part> segment : step.path().segments()) {
            StringBuilder text = new StringBuilder();
            for (PathTemplate.Part part : segment) {
                text.append(part.expression()
                        ? text(evaluate(evaluator, step.pathExpressions().get(part.text()), context, "path"))
                        : part.text());
            }
            String value = text.toString();
            if (value.isEmpty() || value.equals(".") || value.equals("..")) {
                throw new BuildException("path", "unsafe path segment '" + value + "'", null);
            }
            segments.add(value);
        }
        Map<String, String> query = new LinkedHashMap<>();
        step.query().forEach((name, expression) -> {
            JsonNode value = evaluate(evaluator, expression, context, "query." + name);
            if (present(value)) {
                query.put(name, text(value));
            }
        });
        URI uri;
        try {
            uri = UriBuilder.build(baseUrl, segments, query);
        } catch (IllegalArgumentException e) {
            throw new BuildException("path", e.getMessage(), e);
        }

        Map<String, String> headers = new LinkedHashMap<>(fixed);
        java.util.Set<String> reserved = new java.util.HashSet<>();
        fixed.keySet().forEach(name -> reserved.add(name.toLowerCase(Locale.ROOT)));
        step.headers().forEach((name, expression) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if ((reserved.contains(lower) && !lower.equals("accept")) || lower.startsWith("x-apiorch-")) {
                return;
            }
            JsonNode value = evaluate(evaluator, expression, context, "headers." + name);
            if (present(value)) {
                headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
                headers.put(name, text(value));
            }
        });

        JsonNode body = null;
        byte[] bytes = null;
        if (step.body() != null) {
            body = evaluate(evaluator, step.body(), context, "body");
            if (body.isMissingNode()) {
                body = null;
            } else {
                try {
                    bytes = DslJsonMapper.get().writeValueAsBytes(body);
                } catch (JsonProcessingException e) {
                    throw new BuildException("body", "cannot be serialised", e);
                }
                headers.putIfAbsent("Content-Type", "application/json");
            }
        }
        return new StepRequest(step.definition().method().name(), uri, headers, body, bytes);
    }

    private static JsonNode evaluate(ExpressionEvaluator evaluator, CompiledExpression expression, JsonNode context,
            String field) {
        try {
            return evaluator.evaluate(expression, context);
        } catch (ExpressionException e) {
            String code = e.code() != null ? " [" + e.code() + "]" : "";
            throw new BuildException(field, e.getMessage() + code, e);
        }
    }

    private static boolean present(JsonNode value) {
        return value != null && !value.isMissingNode() && !value.isNull();
    }

    /** A value as it appears in a URL or header: strings unquoted, everything else as JSON. */
    private static String text(JsonNode value) {
        if (!present(value)) {
            return "";
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }
}
