package com.crisil.apiorch.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.definition.StepDefinition.OnFailure;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;

/**
 * What a step's final failure does to the run (US-4.6). Pure, so the coordinator and the dry run (US-3.5) share it:
 * <ul>
 *   <li>{@code FAIL_RUN} (the default) stops the run;</li>
 *   <li>{@code CONTINUE} keeps the step {@code FAILED} and lets other branches go on;</li>
 *   <li>{@code FALLBACK} evaluates {@code fallback} over the run context plus {@code error = {code, message,
 *       httpStatus}}; its result becomes the step's body. A fallback that fails is {@code EXPRESSION_ERROR} and
 *       stops the run.</li>
 * </ul>
 */
public final class FailurePolicyApplier {

    /** The step's failure, after retries. */
    public record Failure(String code, String message, Integer httpStatus) {
    }

    public sealed interface PolicyOutcome {
    }

    /** The run stops; the step is {@code FAILED} with {@code code} and {@code message}. */
    public record StopRun(String code, String message) implements PolicyOutcome {
    }

    /** The step stays {@code FAILED}; the run goes on. */
    public record Continue() implements PolicyOutcome {
    }

    /** The step is {@code SUCCEEDED} with {@code fallback_applied} and this body. */
    public record FallbackApplied(JsonNode body) implements PolicyOutcome {
    }

    private FailurePolicyApplier() {
    }

    public static PolicyOutcome apply(CompiledStep step, JsonNode context, Failure failure,
            ExpressionEvaluator evaluator) {
        OnFailure policy = step.definition().effectiveOnFailure();
        if (policy == OnFailure.CONTINUE) {
            return new Continue();
        }
        if (policy == OnFailure.FALLBACK && step.fallback() != null) {
            ObjectNode ctx = (ObjectNode) context.deepCopy();
            ObjectNode error = ctx.putObject("error");
            error.put("code", failure.code()).put("message", failure.message());
            if (failure.httpStatus() != null) {
                error.put("httpStatus", failure.httpStatus());
            }
            try {
                return new FallbackApplied(evaluator.evaluate(step.fallback(), ctx));
            } catch (ExpressionException e) {
                return new StopRun("EXPRESSION_ERROR", "fallback failed (" + e.code() + ") after " + failure.code());
            }
        }
        return new StopRun(failure.code(), failure.message());
    }
}
