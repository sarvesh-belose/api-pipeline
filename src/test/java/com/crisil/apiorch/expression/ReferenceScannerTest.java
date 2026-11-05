package com.crisil.apiorch.expression;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** US-3.2 AC10: named references are found; every other use of {@code steps} fails closed. */
class ReferenceScannerTest {

    @Test
    void dottedAndLiteralBracketReferences() {
        ReferenceScanner.References r = ReferenceScanner.scan(
                "{ 'a': steps.api1.body.id, 'b': steps['api2'].body.ref, 'c': steps[ \"api3\" ].status }");
        assertThat(r.steps()).containsExactly("api2", "api3", "api1");
        assertThat(r.dynamic()).isFalse();
        assertThat(ReferenceScanner.scan(null).steps()).isEmpty();
    }

    @Test
    void textAndOtherFieldsNamedStepsAreNotReferences() {
        assertThat(ReferenceScanner.scan("input.steps & 'steps.* and steps' & \"it's\" & $steps")).satisfies(r -> {
            assertThat(r.steps()).isEmpty();
            assertThat(r.dynamic()).isFalse();
        });
        assertThat(ReferenceScanner.scan("'a\\'steps' & input.x").dynamic()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"$lookup(steps, $key)", "steps.*", "steps[$x]", "steps", "$keys(steps)",
            "steps.API1", "steps[0]"})
    void dynamicAccessFailsClosed(String expr) {
        assertThat(ReferenceScanner.scan(expr).dynamic()).as(expr).isTrue();
    }

    @Test
    void stringBlankingKeepsLength() {
        String text = "a 'bc' \"d\\\"e\" f";
        assertThat(ReferenceScanner.withoutStringLiterals(text)).hasSameSizeAs(text).startsWith("a '  '");
    }
}
