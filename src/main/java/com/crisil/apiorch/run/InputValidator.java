package com.crisil.apiorch.run;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.PipelineDefinition;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;
import com.crisil.apiorch.observability.GlobalExceptionHandler.FieldError;
import com.crisil.apiorch.observability.ValidationFailedException;

/**
 * Checks a run's input against the pipeline's {@code input} spec before any row is written (US-4.1 AC5): the body is
 * a JSON object, every {@code required} field is present and not null, and {@code validWhen} (evaluated with the
 * input as its root) is {@code true}.
 */
@Component
public class InputValidator {

    private final ExpressionEvaluator evaluator;

    public InputValidator(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public void check(CompiledPipeline pipeline, JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new ValidationFailedException(List.of(new FieldError("/", "the run input must be a JSON object")));
        }
        List<FieldError> errors = new ArrayList<>();
        PipelineDefinition.InputSpec spec = pipeline.definition().input();
        if (spec != null && spec.required() != null) {
            for (String name : spec.required()) {
                if (!input.hasNonNull(name)) {
                    errors.add(new FieldError("/" + name, "missing: " + name));
                }
            }
        }
        if (errors.isEmpty() && pipeline.inputValidWhen() != null) {
            boolean ok;
            try {
                ok = evaluator.evaluate(pipeline.inputValidWhen(), input).asBoolean(false);
            } catch (ExpressionException e) {
                ok = false;
            }
            if (!ok) {
                errors.add(new FieldError("/", "validWhen failed"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
    }
}
