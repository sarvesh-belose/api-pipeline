package com.crisil.apiorch.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.PipelineCompiler;
import com.crisil.apiorch.engine.FailurePolicyApplier.Continue;
import com.crisil.apiorch.engine.FailurePolicyApplier.Failure;
import com.crisil.apiorch.engine.FailurePolicyApplier.FallbackApplied;
import com.crisil.apiorch.engine.FailurePolicyApplier.StopRun;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.GuardedJsonataEvaluator;

import testsupport.engine.TestPipelines;

/** US-4.6: the three policies, the {@code error} binding, and a fallback that fails. */
class FailurePolicyApplierTest {

    private final ExpressionEvaluator evaluator = new GuardedJsonataEvaluator(1000, 200, 4096, 64, new ObjectMapper());
    private final Failure timeout = new Failure("TIMEOUT", "read timed out", null);

    private CompiledPipeline compile(String policy, String fallback) {
        ObjectNode doc = TestPipelines.sequential("FPA", "api1", "api2");
        ObjectNode step = (ObjectNode) doc.path("steps").get(1);
        if (policy != null) {
            step.put("onFailure", policy);
        }
        if (fallback != null) {
            step.put("fallback", fallback);
        }
        return new PipelineCompiler(evaluator).compile(1, TestPipelines.bind(doc), "");
    }

    private static ObjectNode context() {
        ObjectNode ctx = JsonNodeFactory.instance.objectNode();
        ctx.putObject("input").put("name", "Asha");
        return ctx;
    }

    @Test
    void failRunIsTheDefaultAndStops() {
        assertThat(FailurePolicyApplier.apply(compile(null, null).step("api2"), context(), timeout, evaluator))
                .isEqualTo(new StopRun("TIMEOUT", "read timed out"));
    }

    @Test
    void continueKeepsGoing() {
        assertThat(FailurePolicyApplier.apply(compile("CONTINUE", null).step("api2"), context(), timeout, evaluator))
                .isInstanceOf(Continue.class);
    }

    @Test
    void fallbackSeesTheErrorAndTheContext() {
        CompiledPipeline p = compile("FALLBACK", "{ 'score': 0, 'reason': error.code, 'status': error.httpStatus, "
                + "'who': input.name }");
        Object out = FailurePolicyApplier.apply(p.step("api2"), context(), new Failure("HTTP_ERROR", "HTTP 503", 503),
                evaluator);
        assertThat(out).isInstanceOf(FallbackApplied.class);
        assertThat(((FallbackApplied) out).body().toString())
                .isEqualTo("{\"score\":0,\"reason\":\"HTTP_ERROR\",\"status\":503,\"who\":\"Asha\"}");
    }

    @Test
    void aFailingFallbackIsAnExpressionErrorThatStops() {
        Object out = FailurePolicyApplier.apply(compile("FALLBACK", "{ 'v': 1 + 'a' }").step("api2"), context(),
                timeout, evaluator);
        assertThat(out).isInstanceOf(StopRun.class);
        assertThat(((StopRun) out).code()).isEqualTo("EXPRESSION_ERROR");
    }
}
