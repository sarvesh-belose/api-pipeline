package com.crisil.apiorch.definition;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One problem in a definition (US-3.2): a rule code, its severity, the step it concerns (if any) and a JSON pointer
 * into the definition, for example {@code /steps/3/body}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DefinitionIssue(String code, Severity severity, String stepId, String path, String message) {

    public enum Severity { ERROR, WARNING }

    public static DefinitionIssue error(String code, String stepId, String path, String message) {
        return new DefinitionIssue(code, Severity.ERROR, stepId, path, message);
    }

    public static DefinitionIssue warning(String code, String stepId, String path, String message) {
        return new DefinitionIssue(code, Severity.WARNING, stepId, path, message);
    }
}
