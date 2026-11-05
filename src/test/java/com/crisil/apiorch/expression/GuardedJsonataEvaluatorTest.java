package com.crisil.apiorch.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.expression.ExpressionException.Kind;

/**
 * ADR-002 as executable findings (the JsonataSpikeTest of US-3.1): mapping, numbers, undefined, the hostile suite
 * aborted within the budget, the pre-compile guards, and per-call isolation under concurrency.
 */
class GuardedJsonataEvaluatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final GuardedJsonataEvaluator evaluator = new GuardedJsonataEvaluator(1000, 200, 4096, 64, json);

    private JsonNode eval(String expr, String context) throws Exception {
        return evaluator.evaluate(evaluator.compile(expr), json.readTree(context));
    }

    private static Kind kind(Runnable r) {
        try {
            r.run();
        } catch (ExpressionException e) {
            return e.kind();
        }
        throw new AssertionError("expected an ExpressionException");
    }

    @Test
    void mapsFieldsFromEarlierSteps() throws Exception {
        JsonNode out = eval("{ 'customerId': steps.api1.body.id, 'branch': $substring(steps.api1.body.acc, 4, 3) }",
                "{\"steps\":{\"api1\":{\"body\":{\"id\":\"C-77\",\"acc\":\"ACCT123456\"}}}}");
        assertThat(out.path("customerId").asText()).isEqualTo("C-77");
        assertThat(out.path("branch").asText()).isEqualTo("123");
        assertThat(evaluator.compile("input.x").source()).isEqualTo("input.x");
    }

    @Test
    void numbersNullsAndUndefined() throws Exception {
        assertThat(eval("big", "{\"big\":12345678901234567}").asLong()).isEqualTo(12345678901234567L);
        assertThat(eval("missing.path", "{}").isMissingNode()).isTrue();
        assertThat(eval("{'a': null}", "{}").path("a").isNull()).as("a nested null is kept").isTrue();
        assertThat(eval("null", "{}").isMissingNode()).as("the library returns a top-level null as undefined").isTrue();
        assertThat(eval("n", "{\"n\":null}").isMissingNode()).as("a null read from the input is undefined").isTrue();
        assertThat(eval("1 + 1", "{}").asInt()).isEqualTo(2);
        assertThat(evaluator.evaluate(evaluator.compile("'x'"), null).asText()).isEqualTo("x");
    }

    @Test
    void syntaxErrorsCarryCodeAndPosition() {
        assertThatThrownBy(() -> evaluator.compile("{ 'a': 1 ")).isInstanceOfSatisfying(ExpressionException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.SYNTAX);
            assertThat(e.code()).startsWith("S0");
            assertThat(e.position()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void runtimeErrorsAreEvaluationErrors() {
        assertThat(kind(() -> evaluator.evaluate(evaluator.compile("$number('abc') + 'x'"), json.createObjectNode())))
                .isEqualTo(Kind.EVALUATION);
    }

    @Test
    void preCompileGuards() {
        assertThatThrownBy(() -> evaluator.compile("1+".repeat(3000) + "1"))
                .isInstanceOfSatisfying(ExpressionException.class, e -> assertThat(e.code()).isEqualTo("EXPRESSION_TOO_LONG"));
        assertThatThrownBy(() -> evaluator.compile("(".repeat(100) + "1" + ")".repeat(100)))
                .isInstanceOfSatisfying(ExpressionException.class, e -> assertThat(e.code()).isEqualTo("EXPRESSION_TOO_DEEP"));
        assertThatThrownBy(() -> evaluator.compile("$match(input.a, /ab+/)"))
                .isInstanceOfSatisfying(ExpressionException.class, e -> assertThat(e.code()).isEqualTo("REGEX_NOT_ALLOWED"));
        assertThat(evaluator.compile("input.a / 2")).isNotNull();
        assertThat(evaluator.compile("'a/b/' & '(((('")).isNotNull();
    }

    @Test
    @Timeout(10)
    void hostileExpressionsAreAbortedWithinTheBudget() {
        // The probe's budget (ADR-002): the same bounds mechanism as production's 1 s, so the suite stays quick.
        GuardedJsonataEvaluator evaluator = new GuardedJsonataEvaluator(100, 200, 4096, 64, json);
        String[] hostile = {
                "($f := function($x){ $f($x + 1) }; $f(0))",
                "$count([1..3000].([1..3000]))",
                "$reduce([1..10000000], function($a, $b){ $a + $b })",
                "$length($pad('x', 50000000))"};
        for (String expr : hostile) {
            long start = System.nanoTime();
            assertThat(kind(() -> evaluator.evaluate(evaluator.compile(expr), json.createObjectNode())))
                    .as(expr).isIn(Kind.BUDGET_EXCEEDED, Kind.EVALUATION);
            assertThat((System.nanoTime() - start) / 1_000_000).as(expr).isLessThan(3000);
        }
    }

    @Test
    void padKeepsItsSemanticsUnderTheCap() throws Exception {
        assertThat(eval("$pad('ab', 4)", "{}").asText()).isEqualTo("ab  ");
        assertThat(eval("$pad('ab', -4, '0')", "{}").asText()).isEqualTo("00ab");
        assertThat(eval("$pad('abcdef', 3)", "{}").asText()).isEqualTo("abcdef");
        assertThat(GuardedJsonataEvaluator.pad(null, 3, null)).isNull();
        assertThat(GuardedJsonataEvaluator.pad("a", null, null)).isEqualTo("a");
    }

    @Test
    void concurrentEvaluationsNeverMixResults() throws Exception {
        CompiledExpression expr = evaluator.compile("{ 'id': id, 'root': $$.id }");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            int base = t * 1000;
            results.add(pool.submit(() -> {
                int wrong = 0;
                for (int i = 0; i < 1000; i++) {
                    JsonNode out = evaluator.evaluate(expr, json.createObjectNode().put("id", base + i));
                    if (out.path("id").asInt() != base + i || out.path("root").asInt() != base + i) {
                        wrong++;
                    }
                }
                return wrong;
            }));
        }
        int wrong = 0;
        for (Future<Integer> f : results) {
            wrong += f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(wrong).isZero();
    }
}
