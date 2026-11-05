package com.crisil.apiorch.pipeline;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.definition.DefinitionBinder;
import com.crisil.apiorch.definition.DefinitionIssue;
import com.crisil.apiorch.definition.DefinitionValidator;
import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.definition.ValidationReport;
import com.crisil.apiorch.endpoint.EndpointRepository;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.pipeline.dto.ValidateResponse;

/**
 * Validates the draft, or a definition sent in the request, without saving anything (US-3.2 AC9). Structural
 * problems are reported as {@code SCHEMA_INVALID} in the verdict rather than as a 400, so an editor sees every
 * problem in one place.
 */
@Service
public class ValidateService {

    private final PipelineDefinitionRepository definitions;
    private final EndpointRepository endpoints;
    private final DefinitionBinder binder;
    private final DefinitionValidator validator;

    public ValidateService(PipelineDefinitionRepository definitions, EndpointRepository endpoints,
            Validator beanValidator, DefinitionValidator validator, ApiorchProperties properties) {
        this.definitions = definitions;
        this.endpoints = endpoints;
        this.binder = new DefinitionBinder(beanValidator, properties.definition().maxSteps());
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public ValidateResponse validate(String code, JsonNode body, boolean detail) {
        String checked;
        JsonNode document;
        if (body != null && body.hasNonNull("definition")) {
            checked = "body";
            document = body.get("definition");
        } else {
            checked = "draft";
            document = definitions.findByCodeAndVersion(code, PipelineDefinitionEntity.DRAFT_VERSION)
                    .map(d -> read(d.getContent()))
                    .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "pipeline-not-found",
                            "Pipeline '" + code + "' has no draft to validate; send a definition in the body."));
        }
        DefinitionBinder.Result bound = binder.bind(document, "");
        if (!bound.ok()) {
            return response(ValidationReport.invalid(bound.errors()), checked, detail);
        }
        ValidationReport report = validator.validate(bound.definition(), EndpointStates.of(endpoints));
        if (!code.equals(bound.definition().code())) {
            List<DefinitionIssue> errors = new ArrayList<>(report.errors());
            errors.add(0, DefinitionIssue.error("CODE_MISMATCH", null, "/code", "definition.code '"
                    + bound.definition().code() + "' is not the pipeline code '" + code + "'"));
            report = new ValidationReport(false, report.executionPlan(), report.postCompletion(),
                    report.planDescription(), List.copyOf(errors), report.warnings());
        }
        return response(report, checked, detail);
    }

    static ValidateResponse response(ValidationReport report, String checked, boolean detail) {
        List<List<Object>> plan = new ArrayList<>();
        for (List<String> level : report.executionPlan()) {
            List<Object> entries = new ArrayList<>();
            for (String id : level) {
                entries.add(detail ? new ValidateResponse.PlanEntry(id, report.postCompletion().contains(id)) : id);
            }
            plan.add(entries);
        }
        return new ValidateResponse(report.valid(), checked, plan, report.planDescription(), report.errors(),
                report.warnings());
    }

    private static JsonNode read(String content) {
        try {
            return DslJsonMapper.get().readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored draft is not readable", e);
        }
    }
}
