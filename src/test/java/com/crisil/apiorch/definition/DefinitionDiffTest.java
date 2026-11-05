package com.crisil.apiorch.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import testsupport.engine.TestPipelines;

/** US-3.4: the semantic diff for a pure reorder, field changes, added and removed steps, and settings. */
class DefinitionDiffTest {

    private static DefinitionDiff.Side side(int version, ObjectNode doc, List<List<String>> plan) {
        return new DefinitionDiff.Side(version, doc, TestPipelines.bind(doc), plan);
    }

    private static List<List<String>> chain(String... ids) {
        return java.util.Arrays.stream(ids).map(List::of).toList();
    }

    private static ObjectNode step(ObjectNode doc, String id) {
        for (var s : doc.withArray("steps")) {
            if (s.path("id").asText().equals(id)) {
                return (ObjectNode) s;
            }
        }
        throw new IllegalArgumentException(id);
    }

    @Test
    void pureReorderShowsOrderPlanAndEffectiveDependencies() {
        DefinitionDiff.Result r = DefinitionDiff.diff(
                side(1, TestPipelines.onboardV1(), chain("api1", "api2", "api3", "api4", "api5")),
                side(2, TestPipelines.onboardReordered(), chain("api1", "api2", "api5", "api4", "api3")));
        assertThat(r.from()).isEqualTo(1);
        assertThat(r.to()).isEqualTo(2);
        assertThat(r.orderChanged()).isTrue();
        assertThat(r.fromOrder()).containsExactly("api1", "api2", "api3", "api4", "api5");
        assertThat(r.toOrder()).containsExactly("api1", "api2", "api5", "api4", "api3");
        assertThat(r.toPlan()).isEqualTo(chain("api1", "api2", "api5", "api4", "api3"));
        assertThat(r.addedSteps()).isEmpty();
        assertThat(r.removedSteps()).isEmpty();
        assertThat(r.changedSettings()).isEmpty();
        assertThat(r.changedSteps()).extracting(DefinitionDiff.StepChange::id).containsExactly("api5", "api4", "api3");
        DefinitionDiff.FieldChange api4 = r.changedSteps().get(1).fields().get(0);
        assertThat(api4.field()).isEqualTo("dependsOn(effective)");
        assertThat(api4.from().toString()).isEqualTo("[\"api3\"]");
        assertThat(api4.to().toString()).isEqualTo("[\"api5\"]");
    }

    @Test
    void nestedFieldChangeAndAddedStep() {
        ObjectNode v2 = TestPipelines.onboardReordered();
        step(v2, "api2").putObject("retry").put("maxAttempts", 3);
        ObjectNode v3 = TestPipelines.sequential("DEMO_ONBOARD", "api1", "api2", "api5", "api4", "api3", "api6");
        step(v3, "api2").putObject("retry").put("maxAttempts", 5);
        DefinitionDiff.Result r = DefinitionDiff.diff(side(2, v2, List.of()), side(3, v3, List.of()));
        assertThat(r.addedSteps()).containsExactly("api6");
        assertThat(r.removedSteps()).isEmpty();
        assertThat(r.changedSteps()).hasSize(1);
        DefinitionDiff.StepChange api2 = r.changedSteps().get(0);
        assertThat(api2.id()).isEqualTo("api2");
        assertThat(api2.fields()).singleElement().satisfies(f -> {
            assertThat(f.field()).isEqualTo("retry.maxAttempts");
            assertThat(f.from().asInt()).isEqualTo(3);
            assertThat(f.to().asInt()).isEqualTo(5);
        });
        assertThat(r.orderChanged()).isTrue();
    }

    @Test
    void removedStepFieldAddedOrDroppedAndSettings() {
        ObjectNode a = TestPipelines.onboardV1();
        a.put("runTimeoutMs", 30000);
        step(a, "api1").put("timeoutMs", 2000);
        ObjectNode b = TestPipelines.sequential("DEMO_ONBOARD", "api1", "api2", "api3", "api4");
        b.put("runTimeoutMs", 45000);
        b.putObject("defaults").put("timeoutMs", 1500);
        step(b, "api2").put("idempotent", true);
        List<List<String>> plan = chain("api1", "api2", "api3", "api4");
        DefinitionDiff.Result r = DefinitionDiff.diff(side(4, a, plan), side(5, b, plan));
        assertThat(r.removedSteps()).containsExactly("api5");
        assertThat(r.orderChanged()).isTrue();
        assertThat(r.changedSettings()).extracting(DefinitionDiff.FieldChange::field)
                .containsExactly("defaults", "runTimeoutMs");
        assertThat(r.changedSettings().get(0).from()).isNull();
        assertThat(r.changedSteps()).extracting(DefinitionDiff.StepChange::id).containsExactly("api1", "api2");
        assertThat(r.changedSteps().get(0).fields().get(0).field()).isEqualTo("timeoutMs");
        assertThat(r.changedSteps().get(0).fields().get(0).to()).isNull();
        assertThat(r.changedSteps().get(1).fields().get(0).field()).isEqualTo("idempotent");
    }

    @Test
    void identicalVersionsHaveNoChanges() {
        DefinitionDiff.Result r = DefinitionDiff.diff(side(1, TestPipelines.onboardV1(), chain("api1")),
                side(2, TestPipelines.onboardV1(), chain("api1")));
        assertThat(r.orderChanged()).isFalse();
        assertThat(r.changedSteps()).isEmpty();
        assertThat(r.changedSettings()).isEmpty();
    }
}
