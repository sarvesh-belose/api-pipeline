package com.crisil.apiorch.definition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Binds a DSL document to {@link PipelineDefinition} and checks its structure (US-3.1 AC3, AC6): unknown fields,
 * types, required fields, patterns and the step limit. Every error carries a JSON pointer rooted at
 * {@code pointerPrefix}. Semantics are not checked here.
 */
public final class DefinitionBinder {

    /** The structural outcome: a definition, or the errors that stopped it. */
    public record Result(PipelineDefinition definition, List<DefinitionIssue> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final Validator validator;
    private final int maxSteps;

    public DefinitionBinder(Validator validator, int maxSteps) {
        this.validator = validator;
        this.maxSteps = maxSteps;
    }

    public Result bind(JsonNode document, String pointerPrefix) {
        if (document == null || !document.isObject()) {
            return new Result(null, List.of(DefinitionIssue.error("SCHEMA_INVALID", null, pointerPrefix, "must be a JSON object")));
        }
        PipelineDefinition definition;
        try {
            definition = DslJsonMapper.get().treeToValue(document, PipelineDefinition.class);
        } catch (JsonProcessingException e) {
            return new Result(null, List.of(bindingIssue(e, pointerPrefix)));
        }
        List<DefinitionIssue> errors = new ArrayList<>();
        for (ConstraintViolation<PipelineDefinition> v : validator.validate(definition)) {
            errors.add(DefinitionIssue.error("SCHEMA_INVALID", null, pointerPrefix + pointer(v.getPropertyPath()), v.getMessage()));
        }
        if (definition.steps() != null && definition.steps().size() > maxSteps) {
            errors.add(DefinitionIssue.error("TOO_MANY_STEPS", null, pointerPrefix + "/steps",
                    "has " + definition.steps().size() + " steps; the limit is " + maxSteps));
        }
        errors.sort(Comparator.comparing(DefinitionIssue::path));
        return new Result(errors.isEmpty() ? definition : null, errors);
    }

    static DefinitionIssue bindingIssue(JsonProcessingException e, String prefix) {
        String pointer = prefix;
        if (e instanceof JsonMappingException jme) {
            StringBuilder sb = new StringBuilder();
            for (JsonMappingException.Reference ref : jme.getPath()) {
                sb.append('/').append(ref.getFieldName() != null ? ref.getFieldName() : String.valueOf(ref.getIndex()));
            }
            pointer = prefix + sb;
        }
        String message;
        if (e instanceof UnrecognizedPropertyException upe) {
            message = "unknown field '" + upe.getPropertyName() + "'";
        } else if (e instanceof InvalidFormatException ife) {
            message = "invalid value '" + ife.getValue() + "'" + (ife.getTargetType() != null && ife.getTargetType().isEnum()
                    ? "; allowed: " + java.util.Arrays.toString(ife.getTargetType().getEnumConstants()) : "");
        } else if (e instanceof MismatchedInputException mie) {
            message = "has the wrong type" + (mie.getTargetType() != null
                    ? "; expected " + mie.getTargetType().getSimpleName() : "");
        } else {
            message = "is not valid";
        }
        return DefinitionIssue.error("SCHEMA_INVALID", null, pointer, message);
    }

    /** {@code steps[2].id} becomes {@code /steps/2/id}. */
    static String pointer(Path path) {
        StringBuilder sb = new StringBuilder();
        for (Path.Node node : path) {
            // Hibernate Validator puts a collection index on the node after the collection.
            if (node.getIndex() != null) {
                sb.append('/').append(node.getIndex());
            } else if (node.getKey() != null) {
                sb.append('/').append(node.getKey());
            }
            if (node.getName() != null && node.getKind() != jakarta.validation.ElementKind.CONTAINER_ELEMENT) {
                sb.append('/').append(node.getName());
            }
        }
        return sb.toString();
    }
}
