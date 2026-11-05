package com.crisil.apiorch.definition;

/**
 * One problem in a definition, located by a JSON pointer into the request (for example
 * {@code /definition/steps/2/dependOn}).
 */
public record DefinitionIssue(String code, String pointer, String message) {
}
