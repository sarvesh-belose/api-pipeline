package com.crisil.apiorch.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.crisil.apiorch.definition.StepDefinition.Method;
import com.crisil.apiorch.definition.StepDefinition.Mode;
import com.crisil.apiorch.definition.StepDefinition.OnFailure;

/** The US-3.1 semantic subset: references, cycles, endpoints, required companions and order warnings. */
class DefinitionValidatorTest {

    private final DefinitionValidator validator = new DefinitionValidator(1);
    private static final Set<String> ENDPOINTS = Set.of("svc");

    private static StepDefinition step(String id, List<String> dependsOn) {
        return new StepDefinition(id, null, "svc", Method.GET, "/x", null, null, null, null, dependsOn, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static PipelineDefinition pipeline(StepDefinition... steps) {
        return new PipelineDefinition(null, "CODE_A", null, null, null, null, null, null, null, List.of(steps), null);
    }

    private ValidationReport validate(PipelineDefinition d) {
        return validator.validate(d, ENDPOINTS, "/definition");
    }

    @Test
    void implicitChainIsValid() {
        PipelineDefinition d = pipeline(step("a", null), step("b", null), step("c", null));
        assertThat(validate(d).valid()).isTrue();
        assertThat(DefinitionValidator.effectiveDependsOn(d, 0)).isEmpty();
        assertThat(DefinitionValidator.effectiveDependsOn(d, 2)).containsExactly("b");
    }

    @Test
    void unknownStepAndSelfDependency() {
        ValidationReport r = validate(pipeline(step("a", List.of("zzz")), step("b", List.of("b"))));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).extracting(DefinitionIssue::code).contains("UNKNOWN_STEP", "CYCLE");
        assertThat(r.errors()).extracting(DefinitionIssue::pointer).contains("/definition/steps/0/dependsOn/0",
                "/definition/steps/1/dependsOn/0");
    }

    @Test
    void cycleAcrossStepsIsReportedOnce() {
        ValidationReport r = validate(pipeline(step("a", List.of("c")), step("b", List.of("a")), step("c", List.of("b"))));
        assertThat(r.errors()).filteredOn(e -> e.code().equals("CYCLE")).hasSize(1);
        assertThat(r.warnings()).extracting(DefinitionIssue::code).containsExactly("ORDER_DIFFERS");
    }

    @Test
    void duplicateIdsUnknownEndpointsAndMissingCompanions() {
        StepDefinition fallbackWithout = new StepDefinition("b", null, "other", Method.POST, "/x", null, null, null,
                Mode.ASYNC_CALLBACK, null, null, null, null, null, null, OnFailure.FALLBACK, " ",
                new StepDefinition.CompensateSpec("gone", Method.DELETE, "/x", null, null, null),
                new StepDefinition.CallbackSpec(null, null), null, null);
        ValidationReport r = validate(pipeline(step("a", null), step("a", null), fallbackWithout));
        assertThat(r.errors()).extracting(DefinitionIssue::code).containsExactlyInAnyOrder("DUPLICATE_STEP_ID",
                "UNKNOWN_ENDPOINT", "UNKNOWN_ENDPOINT", "FALLBACK_REQUIRED", "CALLBACK_REQUIRED");
        assertThat(r.errors()).extracting(DefinitionIssue::pointer).contains("/definition/steps/2/compensate/endpoint");
    }

    @Test
    void reorderedListWarnsButStaysValid() {
        ValidationReport r = validate(pipeline(step("a", List.of()), step("b", List.of("c")), step("c", List.of("a"))));
        assertThat(r.valid()).isTrue();
        assertThat(r.warnings()).singleElement().satisfies(w -> assertThat(w.pointer())
                .isEqualTo("/definition/steps/1/dependsOn/0"));
    }
}
