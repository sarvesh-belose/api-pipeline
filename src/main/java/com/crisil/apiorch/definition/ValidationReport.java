package com.crisil.apiorch.definition;

import java.util.List;

/**
 * The semantic verdict on a structurally valid definition (US-3.1 AC4, US-3.2). Non-blocking when a draft is
 * saved; blocking at validate and publish.
 */
public record ValidationReport(boolean valid, List<DefinitionIssue> errors, List<DefinitionIssue> warnings) {

    public static ValidationReport of(List<DefinitionIssue> errors, List<DefinitionIssue> warnings) {
        return new ValidationReport(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }
}
