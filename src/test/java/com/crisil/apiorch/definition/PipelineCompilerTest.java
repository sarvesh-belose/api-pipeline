package com.crisil.apiorch.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import testsupport.engine.FakeExpressionEvaluator;
import testsupport.engine.TestPipelines;

/** The compiled form the engine runs (US-3.3, US-4.3): dependencies, dependents, order and expressions. */
class PipelineCompilerTest {

    private final PipelineCompiler compiler = new PipelineCompiler(new FakeExpressionEvaluator());

    private CompiledPipeline compile(ObjectNode doc) {
        return compiler.compile(3, TestPipelines.bind(doc), "/definition");
    }

    @Test
    void reorderingTheListReordersExecution() {
        CompiledPipeline v1 = compile(TestPipelines.onboardV1());
        CompiledPipeline v2 = compile(TestPipelines.onboardReordered());
        assertThat(v1.order()).containsExactly("api1", "api2", "api3", "api4", "api5");
        assertThat(v2.order()).containsExactly("api1", "api2", "api5", "api4", "api3");
        assertThat(v2.step("api5").dependsOn()).containsExactly("api2");
        assertThat(v2.dependents().get("api2")).containsExactly("api5");
        assertThat(v2.roots()).extracting(CompiledStep::id).containsExactly("api1");
        assertThat(v1.version()).isEqualTo(3);
        assertThat(v1.dslVersion()).isEqualTo(1);
    }

    @Test
    void dagOrderReleasesReadyStepsInListOrder() throws Exception {
        ObjectNode doc = TestPipelines.sequential("DAG_PIPELINE", "a", "c", "b", "d");
        ((ObjectNode) doc.withArray("steps").get(1)).putArray("dependsOn").add("a");
        ((ObjectNode) doc.withArray("steps").get(2)).putArray("dependsOn").add("a");
        ((ObjectNode) doc.withArray("steps").get(3)).putArray("dependsOn").add("b").add("c");
        CompiledPipeline p = compile(doc);
        assertThat(p.order()).containsExactly("a", "c", "b", "d");
        assertThat(p.dependents().get("a")).containsExactlyInAnyOrder("b", "c");
        assertThatThrownBy(() -> p.step("zzz")).hasMessageContaining("zzz");
    }

    @Test
    void everyExpressionFieldIsCompiled() throws Exception {
        ObjectNode doc;
        try (InputStream in = getClass().getResourceAsStream("/dsl/customer-onboard.json")) {
            doc = (ObjectNode) DslJsonMapper.get().readTree(in);
        }
        ObjectNode api2 = (ObjectNode) doc.withArray("steps").get(1);
        api2.putObject("query").put("q", "input.q");
        api2.putObject("headers").put("X-Ref", "run.id");
        api2.put("when", "input.kyc");
        CompiledPipeline p = compile(doc);
        CompiledStep s = p.step("api2");
        assertThat(s.body()).isNotNull();
        assertThat(s.successWhen()).isNotNull();
        assertThat(s.when()).isNotNull();
        assertThat(s.query()).containsOnlyKeys("q");
        assertThat(s.headers()).containsOnlyKeys("X-Ref");
        assertThat(p.step("api3").pathExpressions()).containsOnlyKeys("steps.api1.body.id");
        assertThat(p.step("api5").callbackSuccessWhen()).isNotNull();
        CompiledStep.Compensation comp = p.step("api1").compensation();
        assertThat(comp.endpoint()).isEqualTo("customer-svc");
        assertThat(comp.pathExpressions()).containsOnlyKeys("steps.api1.body.id");
        assertThat(p.inputValidWhen()).isNotNull();
        assertThat(p.output()).isNotNull();
    }

    @Test
    void expressionErrorsAreReportedWithPointers() {
        ObjectNode doc = TestPipelines.onboardV1();
        ((ObjectNode) doc.withArray("steps").get(1)).put("body", "{ !syntax }");
        ((ObjectNode) doc.withArray("steps").get(2)).put("path", "/x/{ $match(input.a, /ab+/) }");
        ((ObjectNode) doc.withArray("steps").get(3)).put("path", "/x/{unclosed");
        assertThatThrownBy(() -> compile(doc)).isInstanceOfSatisfying(PipelineCompiler.CompileException.class, e -> {
            assertThat(e.issues()).extracting(DefinitionIssue::path).containsExactly(
                    "/definition/steps/1/body", "/definition/steps/2/path", "/definition/steps/3/path");
            assertThat(e.issues()).extracting(DefinitionIssue::code).containsExactly(
                    "EXPRESSION_INVALID", "REGEX_NOT_ALLOWED", "PATH_INVALID");
        });
    }
}
