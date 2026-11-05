package com.crisil.apiorch.engine;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.expression.ExpressionEvaluator;

/**
 * The read-only expression context of a run (README §8): {@code input}, {@code steps.<id>.{status, headers, body}}
 * for finished steps, and {@code run.{id, correlationId, pipeline, version}}; plus the run output (US-4.6). Pure.
 */
public final class RunContext {

    private RunContext() {
    }

    /** {@code results}: step id to its stored {@code {status, headers, body}}. */
    public static ObjectNode build(UUID runId, String correlationId, String pipeline, int version, JsonNode input,
            Map<String, JsonNode> results) {
        ObjectNode ctx = JsonNodeFactory.instance.objectNode();
        ctx.set("input", input);
        ObjectNode steps = ctx.putObject("steps");
        results.forEach(steps::set);
        ctx.putObject("run").put("id", runId.toString()).put("correlationId", correlationId).put("pipeline", pipeline)
                .put("version", version);
        return ctx;
    }

    /** One step's entry in {@code steps}. */
    public static ObjectNode stepResult(Integer httpStatus, JsonNode headers, JsonNode body) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (httpStatus != null) {
            node.put("status", httpStatus);
        }
        node.set("headers", headers == null || headers.isMissingNode() ? JsonNodeFactory.instance.objectNode() : headers);
        if (body != null && !body.isMissingNode()) {
            node.set("body", body);
        }
        return node;
    }

    /**
     * The run output: the pipeline's {@code output} expression, or by default
     * {@code { "<stepId>": steps.<stepId>.body }} for every step that has a body (US-3.1).
     */
    public static JsonNode output(CompiledPipeline pipeline, ObjectNode context, ExpressionEvaluator evaluator) {
        if (pipeline.output() != null) {
            return evaluator.evaluate(pipeline.output(), context);
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        context.path("steps").fields().forEachRemaining(e -> {
            if (e.getValue().has("body")) {
                out.set(e.getKey(), e.getValue().get("body"));
            }
        });
        return out;
    }
}
