package com.crisil.apiorch.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.expression.GuardedJsonataEvaluator;

import testsupport.engine.TestPipelines;

/**
 * US-3.2 rules with the real expression engine: the reorder pair (AC1, AC2), graph (AC3, AC6), endpoints and
 * expressions (AC4), post-completion (AC5), feature gates (AC7), retry (AC8) and dynamic references (AC10).
 */
class DefinitionValidatorTest {

    private final DefinitionValidator validator = new DefinitionValidator(
            new DefinitionValidator.Settings(1, 30000, 60000, "METADATA"),
            new GuardedJsonataEvaluator(1000, 200, 4096, 64, new ObjectMapper()));
    private static final Map<String, Boolean> ENDPOINTS = Map.of(TestPipelines.ENDPOINT, true, "off-svc", false);

    private ValidationReport validate(ObjectNode doc) {
        return validator.validate(TestPipelines.bind(doc), ENDPOINTS);
    }

    private static ObjectNode step(ObjectNode doc, int i) {
        return (ObjectNode) doc.withArray("steps").get(i);
    }

    private static List<String> codes(List<DefinitionIssue> issues) {
        return issues.stream().map(DefinitionIssue::code).toList();
    }

    @Test
    void safeReorderIsValidWithItsPlan() {
        ValidationReport r = validate(TestPipelines.onboardReordered());
        assertThat(r.errors()).isEmpty();
        assertThat(r.valid()).isTrue();
        assertThat(r.executionPlan()).containsExactly(List.of("api1"), List.of("api2"), List.of("api5"),
                List.of("api4"), List.of("api3"));
        assertThat(r.planDescription()).containsExactly("1. api1 (demo-svc POST /api1)",
                "2. api2 (demo-svc POST /api2) after api1", "3. api5 (demo-svc POST /api5) after api2",
                "4. api4 (demo-svc POST /api4) after api5", "5. api3 (demo-svc POST /api3) after api4");
    }

    @Test
    void unsafeReorderIsAForwardReference() {
        ObjectNode doc = TestPipelines.onboardReordered();
        step(doc, 3).put("body", "{ 'score': steps.api3.body.score }");
        ValidationReport r = validate(doc);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("FORWARD_REFERENCE");
            assertThat(e.stepId()).isEqualTo("api4");
            assertThat(e.path()).isEqualTo("/steps/3/body");
            assertThat(e.message()).isEqualTo("Step 'api4' reads steps.api3 but 'api3' is not an ancestor of 'api4' "
                    + "(it runs later). Move api3 before api4 or add it to api4.dependsOn.");
        });
    }

    @Test
    void cyclesUnknownAndSelfDependencies() {
        ObjectNode cycle = TestPipelines.sequential("CYCLE_P", "a", "b", "c");
        step(cycle, 0).putArray("dependsOn").add("c");
        ValidationReport r = validate(cycle);
        assertThat(codes(r.errors())).contains("CYCLE_DETECTED");
        assertThat(r.errors().stream().filter(e -> e.code().equals("CYCLE_DETECTED")).findFirst().orElseThrow()
                .message()).contains("a → b → c → a");
        assertThat(r.executionPlan()).isEmpty();

        ObjectNode bad = TestPipelines.sequential("BAD_DEPS", "a", "b");
        step(bad, 0).putArray("dependsOn").add("ghost");
        step(bad, 1).putArray("dependsOn").add("b");
        assertThat(codes(validate(bad).errors())).contains("UNKNOWN_DEPENDENCY", "SELF_DEPENDENCY");

        ObjectNode dup = TestPipelines.sequential("DUP_IDS", "a", "a");
        assertThat(codes(validate(dup).errors())).contains("DUPLICATE_STEP_ID");
    }

    @Test
    void listOrderAndParallelWarnings() {
        ObjectNode doc = TestPipelines.sequential("ORDER_P", "api1", "api2", "api4", "api3");
        step(doc, 2).putArray("dependsOn").add("api3");
        step(doc, 3).putArray("dependsOn").add("api2");
        ValidationReport r = validate(doc);
        assertThat(r.valid()).isTrue();
        assertThat(r.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo("LIST_ORDER_NOT_EXECUTION_ORDER");
            assertThat(w.message()).isEqualTo("api4 is listed before api3 but runs after it");
        });

        ObjectNode parallel = TestPipelines.sequential("PAR_P", "api1", "api2", "api3");
        step(parallel, 2).putArray("dependsOn").add("api1");
        ValidationReport p = validate(parallel);
        assertThat(p.executionPlan()).containsExactly(List.of("api1"), List.of("api2", "api3"));
        assertThat(codes(p.warnings())).contains("STEPS_RUN_IN_PARALLEL");
        assertThat(p.planDescription().get(1)).isEqualTo("2. api2 and api3 in parallel after api1");
    }

    @Test
    void endpointsAndExpressions() {
        ObjectNode doc = TestPipelines.onboardV1();
        step(doc, 0).put("endpoint", "nope-svc");
        step(doc, 1).put("endpoint", "off-svc");
        step(doc, 2).put("body", "{ 'a': 1 ");
        step(doc, 3).put("body", "$match(input.x, /a+/)");
        step(doc, 4).put("path", "/x/../admin");
        ValidationReport r = validate(doc);
        assertThat(codes(r.errors())).containsExactlyInAnyOrder("UNKNOWN_ENDPOINT", "ENDPOINT_DISABLED",
                "EXPRESSION_INVALID", "REGEX_NOT_ALLOWED", "PATH_INVALID");
        DefinitionIssue syntax = r.errors().stream().filter(e -> e.code().equals("EXPRESSION_INVALID")).findFirst()
                .orElseThrow();
        assertThat(syntax.path()).isEqualTo("/steps/2/body");
        assertThat(syntax.message()).containsPattern("S0\\d+ at \\d+");
    }

    @Test
    void referencesOutsideTheGraph() {
        ObjectNode doc = TestPipelines.onboardV1();
        step(doc, 1).put("body", "{ 'x': steps.ghost.body.id }");
        step(doc, 2).put("body", "{ 'x': steps.api3.body.id }");
        step(doc, 3).put("body", "$lookup(steps, 'api1')");
        step(doc, 4).put("body", "{ 'a': steps['api2'].body.ref, 'b': input.steps, 't': 'steps.* in text' }");
        ValidationReport r = validate(doc);
        assertThat(codes(r.errors())).containsExactlyInAnyOrder("UNKNOWN_STEP_REFERENCE", "SELF_REFERENCE",
                "DYNAMIC_STEP_REFERENCE");
        assertThat(r.errors().stream().filter(e -> e.code().equals("DYNAMIC_STEP_REFERENCE")).findFirst()
                .orElseThrow().message()).startsWith("Step 'api4' reads steps dynamically");
    }

    @Test
    void featureGatesInThisRelease() {
        ObjectNode doc = TestPipelines.onboardV1();
        step(doc, 1).put("when", "input.ok");
        step(doc, 2).putObject("compensate").put("method", "DELETE").put("path", "/x");
        step(doc, 3).put("mode", "ASYNC_CALLBACK").putObject("callback").put("timeoutMs", 60000);
        step(doc, 3).put("onFailure", "CONTINUE");
        ValidationReport r = validate(doc);
        assertThat(r.errors()).filteredOn(e -> e.code().equals("FEATURE_NOT_AVAILABLE"))
                .extracting(DefinitionIssue::path)
                .containsExactlyInAnyOrder("/steps/1/when", "/steps/2/compensate", "/steps/3/mode", "/steps/3/callback");
    }

    @Test
    void postCompletionStepsMustNotFailTheRun() {
        ObjectNode doc = TestPipelines.onboardV1();
        step(doc, 3).put("mode", "ASYNC_CALLBACK").put("blocksRunCompletion", false)
                .putObject("callback").put("timeoutMs", 60000);
        ValidationReport r = validate(doc);
        assertThat(r.errors()).filteredOn(e -> e.code().equals("POST_COMPLETION_STEP_MUST_NOT_FAIL_RUN"))
                .extracting(DefinitionIssue::stepId).containsExactlyInAnyOrder("api4", "api5");
        assertThat(r.postCompletion()).containsExactlyInAnyOrder("api4", "api5");
        assertThat(r.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo("STEP_RUNS_AFTER_COMPLETION");
            assertThat(w.stepId()).isEqualTo("api5");
            assertThat(w.message()).contains("api4's callback");
        });
    }

    @Test
    void asyncRetryLoggingAndInputRules() {
        ObjectNode doc = TestPipelines.onboardV1();
        doc.put("syncWaitMs", 99999);
        doc.putObject("input").put("validWhen", "{ !!").putArray("required").add("ok").add("bad-name");
        doc.putObject("logging").putArray("maskKeys").add("customer.dob").add("bad key");
        step(doc, 0).putObject("retry").put("maxAttempts", 3).putArray("retryOn").add("503").add("404");
        step(doc, 1).put("onFailure", "FALLBACK");
        step(doc, 2).put("mode", "ASYNC_CALLBACK");
        step(doc, 3).putObject("callback").put("timeoutMs", 60000);
        step(doc, 4).put("method", "GET").put("timeoutMs", 100000);
        doc.put("runTimeoutMs", 50000);
        ValidationReport r = validate(doc);
        assertThat(codes(r.errors())).contains("SYNC_WAIT_EXCEEDS_MAX", "INPUT_SPEC_INVALID", "MASK_KEY_INVALID",
                "RETRY_ON_INVALID", "FALLBACK_MISSING", "ASYNC_CALLBACK_MISSING", "CALLBACK_ON_SYNC_STEP");
        assertThat(r.errors()).filteredOn(e -> e.code().equals("INPUT_SPEC_INVALID")).hasSize(2);
        assertThat(codes(r.warnings())).contains("RETRY_WITHOUT_IDEMPOTENCY", "STEP_TIMEOUT_EXCEEDS_RUN_TIMEOUT",
                "BODY_ON_GET_OR_DELETE", "EMPTY_ALLOWED_CALLERS");
    }

    @Test
    void outputAndPipelineWarnings() {
        ObjectNode doc = TestPipelines.onboardV1();
        doc.put("output", "{ 'id': steps.api1.body.id, 'x': steps.nope.body, 'y': steps.* }");
        ValidationReport r = validate(doc);
        assertThat(r.errors()).filteredOn(e -> "/output".equals(e.path())).extracting(DefinitionIssue::code)
                .containsExactlyInAnyOrder("UNKNOWN_STEP_REFERENCE", "DYNAMIC_STEP_REFERENCE");
        assertThat(codes(r.warnings())).contains("CAPTURE_LEVEL_NOT_SET", "EMPTY_ALLOWED_CALLERS");
        doc.put("output", "{ 'bad': ");
        assertThat(codes(validate(doc).errors())).contains("EXPRESSION_INVALID");
    }
}
