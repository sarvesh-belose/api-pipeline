package com.crisil.apiorch.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Set;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.StepDefinition.Mode;

/** US-3.1 AC3, AC6 and AC8: strict structural binding with JSON-pointer errors. */
class DslBindingTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private final DefinitionBinder binder = new DefinitionBinder(VALIDATOR, 50);

    private static ObjectNode golden() throws Exception {
        try (InputStream in = DslBindingTest.class.getResourceAsStream("/dsl/customer-onboard.json")) {
            return (ObjectNode) DslJsonMapper.get().readTree(in);
        }
    }

    private DefinitionBinder.Result bind(JsonNode doc) {
        return binder.bind(doc, "/definition");
    }

    @Test
    void theDslMapperFailsOnUnknownProperties() {
        assertThat(DslJsonMapper.get().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    @Test
    void readmeExampleBinds() throws Exception {
        DefinitionBinder.Result result = bind(golden());
        assertThat(result.errors()).isEmpty();
        PipelineDefinition d = result.definition();
        assertThat(d.steps()).extracting(StepDefinition::id).containsExactly("api1", "api2", "api3", "api4", "api5");
        assertThat(d.steps().get(0).dependsOn()).isNull();
        assertThat(d.steps().get(4).dependsOn()).containsExactly("api2");
        assertThat(d.steps().get(4).effectiveMode()).isEqualTo(Mode.ASYNC_CALLBACK);
        assertThat(d.steps().get(4).effectiveBlocksRunCompletion()).isFalse();
        assertThat(d.steps().get(2).effectiveIdempotent()).isTrue();
        assertThat(d.steps().get(1).effectiveIdempotent()).isFalse();
        assertThat(d.steps().get(0).effectiveIdempotent()).isTrue();
        assertThat(d.steps().get(0).compensate().method()).isEqualTo(StepDefinition.Method.DELETE);
    }

    @Test
    void misspeltDependsOnIsRejectedWithItsPointer() throws Exception {
        ObjectNode doc = golden();
        ((ObjectNode) doc.withArray("steps").get(2)).putArray("dependOn").add("api1");
        DefinitionBinder.Result result = bind(doc);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.pointer()).isEqualTo("/definition/steps/2/dependOn");
            assertThat(e.message()).contains("unknown field 'dependOn'");
        });
    }

    @Test
    void missingStepsAndStepFieldsAreListed() throws Exception {
        ObjectNode noSteps = golden();
        noSteps.remove("steps");
        assertThat(bind(noSteps).errors()).extracting(DefinitionIssue::pointer).containsExactly("/definition/steps");

        ObjectNode doc = golden();
        ObjectNode step = (ObjectNode) doc.withArray("steps").get(1);
        step.remove("endpoint");
        step.remove("method");
        step.put("path", "kyc");
        step.put("id", "Bad-Id");
        assertThat(bind(doc).errors()).extracting(DefinitionIssue::pointer).containsExactly(
                "/definition/steps/1/endpoint", "/definition/steps/1/id", "/definition/steps/1/method",
                "/definition/steps/1/path");
    }

    @Test
    void badCodeAndEmptyCallerAreRejected() throws Exception {
        ObjectNode doc = golden();
        doc.put("code", "onboard");
        ((ArrayNode) doc.get("allowedCallers")).add("");
        assertThat(bind(doc).errors()).extracting(DefinitionIssue::pointer)
                .containsExactly("/definition/allowedCallers/2", "/definition/code");
    }

    @Test
    void wrongTypesAndEnumValuesAreRejected() throws Exception {
        ObjectNode doc = golden();
        ((ObjectNode) doc.withArray("steps").get(0)).put("method", "FETCH");
        DefinitionIssue issue = bind(doc).errors().get(0);
        assertThat(issue.pointer()).isEqualTo("/definition/steps/0/method");
        assertThat(issue.message()).contains("FETCH", "POST");

        ObjectNode typed = golden();
        typed.put("runTimeoutMs", "soon");
        assertThat(bind(typed).errors().get(0).pointer()).isEqualTo("/definition/runTimeoutMs");

        assertThat(bind(DslJsonMapper.get().createArrayNode()).errors().get(0).message()).contains("JSON object");
        assertThat(bind(null).ok()).isFalse();
    }

    @Test
    void tooManyStepsIsRejected() throws Exception {
        ObjectNode doc = golden();
        ArrayNode steps = doc.putArray("steps");
        for (int i = 0; i < 51; i++) {
            steps.addObject().put("id", "s" + i).put("endpoint", "e").put("method", "GET").put("path", "/x");
        }
        assertThat(bind(doc).errors()).extracting(DefinitionIssue::code).containsExactly("TOO_MANY_STEPS");
    }

    @Test
    void dslVersionAbsentEqualAndTooHigh() throws Exception {
        ObjectNode doc = golden();
        doc.remove("dslVersion");
        PipelineDefinition absent = bind(doc).definition();
        assertThat(absent.effectiveDslVersion()).isEqualTo(1);
        DefinitionValidator validator = new DefinitionValidator(DslVersion.CURRENT);
        Set<String> endpoints = Set.of("customer-svc", "kyc-svc", "score-svc", "account-svc", "notify-svc");
        assertThat(validator.validate(absent, endpoints, "/definition").valid()).isTrue();

        doc.put("dslVersion", 2);
        ValidationReport tooHigh = validator.validate(bind(doc).definition(), endpoints, "/definition");
        assertThat(tooHigh.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("DSL_VERSION_UNSUPPORTED");
            assertThat(e.message()).contains("2", "1");
        });

        doc.put("dslVersion", 0);
        assertThat(bind(doc).errors()).extracting(DefinitionIssue::pointer).containsExactly("/definition/dslVersion");
    }
}
