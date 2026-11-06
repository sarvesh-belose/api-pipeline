package com.crisil.apiorch.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.definition.PipelineCompiler;
import com.crisil.apiorch.expression.GuardedJsonataEvaluator;

import testsupport.engine.TestPipelines;

/** The pure engine rules: ready set, skips, quiescence, statuses and the run context (US-4.1, US-4.4, US-4.6). */
class RunDecisionTest {

    private final GuardedJsonataEvaluator evaluator = new GuardedJsonataEvaluator(1000, 200, 4096, 64, new ObjectMapper());

    private CompiledPipeline compile(ObjectNode doc) {
        return new PipelineCompiler(evaluator).compile(1, TestPipelines.bind(doc), "");
    }

    private static Map<String, StepState> states(CompiledPipeline p, Map<String, StepState> overrides) {
        Map<String, StepState> out = new LinkedHashMap<>();
        p.steps().forEach(s -> out.put(s.id(), overrides.getOrDefault(s.id(), StepState.pending(s.id()))));
        return out;
    }

    private static StepState done(String id) {
        return new StepState(id, StepStatus.SUCCEEDED, 1, false);
    }

    @Test
    void firstStepIsReadyThenTheChainFollows() {
        CompiledPipeline p = compile(TestPipelines.onboardV1());
        RunDecision.Next first = RunDecision.decide(p, states(p, Map.of()));
        assertThat(first.ready()).extracting(CompiledStep::id).containsExactly("api1");
        assertThat(first.quiescent()).isFalse();
        RunDecision.Next second = RunDecision.decide(p, states(p, Map.of("api1", done("api1"))));
        assertThat(second.ready()).extracting(CompiledStep::id).containsExactly("api2");
        RunDecision.Next waiting = RunDecision.decide(p, states(p, Map.of("api1",
                new StepState("api1", StepStatus.RUNNING, 1, false))));
        assertThat(waiting.ready()).isEmpty();
        assertThat(waiting.skip()).isEmpty();
    }

    @Test
    void parallelBranchesAreReadyTogether() {
        ObjectNode doc = TestPipelines.sequential("DAG_P", "a", "b", "c");
        ((ObjectNode) doc.withArray("steps").get(2)).putArray("dependsOn").add("a");
        CompiledPipeline p = compile(doc);
        assertThat(RunDecision.decide(p, states(p, Map.of("a", done("a")))).ready()).extracting(CompiledStep::id)
                .containsExactly("b", "c");
    }

    @Test
    void failRunStopsEverythingNotStarted() {
        CompiledPipeline p = compile(TestPipelines.onboardV1());
        RunDecision.Next next = RunDecision.decide(p, states(p, Map.of("api1", done("api1"),
                "api2", StepState.failed("api2", true))));
        assertThat(next.skip()).extracting(RunDecision.Skip::reason).containsOnly("RUN_STOPPED");
        assertThat(next.finalStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void continueSkipsDependentsAndEndsWithErrors() {
        CompiledPipeline p = compile(TestPipelines.onboardV1());
        RunDecision.Next next = RunDecision.decide(p, states(p, Map.of("api1", done("api1"),
                "api2", StepState.failed("api2", false))));
        assertThat(next.skip()).extracting(RunDecision.Skip::stepId).containsExactly("api3", "api4", "api5");
        assertThat(next.skip()).extracting(RunDecision.Skip::reason).containsOnly("DEPENDENCY_FAILED");
        assertThat(next.finalStatus()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
    }

    @Test
    void diamondJoinsAndRootsStartTogetherInListOrder() {
        ObjectNode doc = TestPipelines.sequential("DAG_D", "r2", "r1", "b", "c", "j");
        ArrayNode steps = doc.withArray("steps");
        ((ObjectNode) steps.get(0)).putArray("dependsOn");
        ((ObjectNode) steps.get(1)).putArray("dependsOn");
        ((ObjectNode) steps.get(2)).putArray("dependsOn").add("r1");
        ((ObjectNode) steps.get(3)).putArray("dependsOn").add("r1");
        ((ObjectNode) steps.get(4)).putArray("dependsOn").add("b").add("c");
        CompiledPipeline p = compile(doc);
        assertThat(RunDecision.decide(p, states(p, Map.of())).ready()).extracting(CompiledStep::id)
                .containsExactly("r2", "r1");
        assertThat(RunDecision.decide(p, states(p, Map.of("r1", done("r1"), "r2", done("r2"), "b", done("b"),
                "c", new StepState("c", StepStatus.RUNNING, 1, false)))).ready()).isEmpty();
        assertThat(RunDecision.decide(p, states(p, Map.of("r1", done("r1"), "r2", done("r2"), "b", done("b"),
                "c", done("c")))).ready()).extracting(CompiledStep::id).containsExactly("j");
        assertThat(p.ancestors("j")).containsExactlyInAnyOrder("b", "c", "r1");
    }

    @Test
    void aFailedDependencySkipsTransitivelyNamingIt() {
        ObjectNode doc = TestPipelines.sequential("DAG_S", "a", "b", "c", "d");
        ((ObjectNode) doc.withArray("steps").get(2)).putArray("dependsOn").add("a");
        CompiledPipeline p = compile(doc);
        RunDecision.Next next = RunDecision.decide(p, states(p, Map.of("a", done("a"),
                "b", StepState.failed("b", false), "c", new StepState("c", StepStatus.RUNNING, 1, false))));
        assertThat(next.skip()).isEmpty();
        next = RunDecision.decide(p, states(p, Map.of("a", done("a"), "b", StepState.failed("b", false),
                "c", new StepState("c", StepStatus.UNKNOWN, 1, false))));
        assertThat(next.skip()).containsExactly(new RunDecision.Skip("d", "DEPENDENCY_FAILED", "c"));
        doc = TestPipelines.sequential("DAG_T", "a", "b", "c");
        p = compile(doc);
        assertThat(RunDecision.decide(p, states(p, Map.of("a", StepState.failed("a", false)))).skip())
                .containsExactly(new RunDecision.Skip("b", "DEPENDENCY_FAILED", "a"),
                        new RunDecision.Skip("c", "DEPENDENCY_FAILED", "b"));
    }

    @Test
    void allSucceededIsCompleted() {
        CompiledPipeline p = compile(TestPipelines.onboardV1());
        Map<String, StepState> all = new LinkedHashMap<>();
        p.steps().forEach(s -> all.put(s.id(), done(s.id())));
        assertThat(RunDecision.decide(p, all).finalStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void statusPredicates() {
        assertThat(Arrays.stream(RunStatus.values()).filter(RunStatus::terminal)).containsExactlyInAnyOrder(
                RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS, RunStatus.FAILED, RunStatus.COMPENSATED,
                RunStatus.COMPENSATION_FAILED, RunStatus.CANCELLED, RunStatus.TIMED_OUT);
        assertThat(RunStatus.COMPLETED_PENDING_ASYNC.doneForCaller()).isTrue();
        assertThat(RunStatus.RUNNING.doneForCaller()).isFalse();
        assertThat(Arrays.stream(StepStatus.values()).filter(s -> !s.finished())).containsExactlyInAnyOrder(
                StepStatus.PENDING, StepStatus.RUNNING, StepStatus.AWAITING_CALLBACK);
    }

    @Test
    void contextAndDefaultOutput() {
        CompiledPipeline p = compile(TestPipelines.onboardV1());
        UUID runId = UUID.randomUUID();
        ObjectNode result = RunContext.stepResult(201, null, JsonNodeFactory.instance.objectNode().put("id", "C-1"));
        ObjectNode noBody = RunContext.stepResult(null, JsonNodeFactory.instance.objectNode(), MissingNode.getInstance());
        ObjectNode ctx = RunContext.build(runId, "c-1", "P", 1, JsonNodeFactory.instance.objectNode().put("name", "A"),
                Map.of("api1", result, "api2", noBody));
        assertThat(ctx.at("/steps/api1/status").asInt()).isEqualTo(201);
        assertThat(ctx.at("/steps/api2").has("body")).isFalse();
        assertThat(ctx.at("/run/id").asText()).isEqualTo(runId.toString());
        assertThat(RunContext.output(p, ctx, evaluator).fieldNames()).toIterable().containsExactly("api1");
        assertThat(RunContext.stepResult(null, null, null).has("body")).isFalse();

        ObjectNode withOutput = TestPipelines.onboardV1();
        withOutput.put("output", "{ 'customer': steps.api1.body.id }");
        assertThat(RunContext.output(compile(withOutput), ctx, evaluator).path("customer").asText()).isEqualTo("C-1");
    }
}
