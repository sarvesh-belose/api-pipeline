package com.crisil.apiorch.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.definition.DefinitionBinder;
import com.crisil.apiorch.definition.DefinitionIssue;
import com.crisil.apiorch.definition.DefinitionValidator;
import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.definition.PipelineDefinition;
import com.crisil.apiorch.definition.StepDefinition;
import com.crisil.apiorch.definition.ValidationReport;
import com.crisil.apiorch.endpoint.EndpointRepository;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.GlobalExceptionHandler.FieldError;
import com.crisil.apiorch.observability.ValidationFailedException;
import com.crisil.apiorch.pipeline.dto.DraftRequest;
import com.crisil.apiorch.pipeline.dto.DraftResponse;
import com.crisil.apiorch.pipeline.dto.PipelineSummary;

/**
 * Draft CRUD (US-3.1). Saving enforces structure only and returns the semantic verdict as a non-blocking
 * {@code validation} block (AC4). A definition without {@code dslVersion} is stored with {@code dslVersion: 1}
 * as its first field (AC8); otherwise the document is stored as sent, step order included.
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    static final String POINTER = "/definition";

    private final PipelineRepository pipelines;
    private final PipelineDefinitionRepository definitions;
    private final EndpointRepository endpoints;
    private final DefinitionBinder binder;
    private final DefinitionValidator validator;
    private final Clock clock;

    public DraftService(PipelineRepository pipelines, PipelineDefinitionRepository definitions,
            EndpointRepository endpoints, Validator beanValidator, DefinitionValidator validator, Clock clock,
            ApiorchProperties properties) {
        this.pipelines = pipelines;
        this.definitions = definitions;
        this.endpoints = endpoints;
        this.binder = new DefinitionBinder(beanValidator, properties.definition().maxSteps());
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public DraftResponse create(DraftRequest request, String actor) {
        if (request.code() == null || !request.code().matches(PipelineDefinition.CODE_PATTERN)) {
            throw new ValidationFailedException(List.of(new FieldError("/code",
                    "must match " + PipelineDefinition.CODE_PATTERN)));
        }
        PipelineDefinition definition = bind(request, request.code());
        if (pipelines.existsById(request.code())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "already-exists",
                    "Pipeline '" + request.code() + "' already exists; change it with PUT …/draft.");
        }
        Instant now = Instant.now(clock);
        pipelines.save(new PipelineEntity(request.code(), description(request, definition), now, actor));
        String content = content(request.definition());
        PipelineDefinitionEntity draft = definitions.saveAndFlush(
                PipelineDefinitionEntity.draft(request.code(), content, sha256(content), now, actor));
        logSaved("created", draft, definition, actor);
        return response(draft, null, validate(definition));
    }

    @Transactional
    public DraftResponse put(String code, DraftRequest request, Long ifMatch, String actor) {
        if (request.code() != null) {
            throw new ValidationFailedException(List.of(new FieldError("/code",
                    "is taken from the path; omit it from the PUT body")));
        }
        PipelineEntity pipeline = pipeline(code);
        PipelineDefinition definition = bind(request, code);
        Instant now = Instant.now(clock);
        String content = content(request.definition());
        PipelineDefinitionEntity draft = definitions.findByCodeAndVersion(code, PipelineDefinitionEntity.DRAFT_VERSION)
                .orElse(null);
        if (draft == null) {
            draft = PipelineDefinitionEntity.draft(code, content, sha256(content), now, actor);
        } else {
            if (ifMatch == null) {
                throw new ApiProblemException(HttpStatus.PRECONDITION_REQUIRED, "precondition-required",
                        "Send If-Match with the draft's current rowVersion to replace it.");
            }
            if (ifMatch != draft.getRowVersion()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "version-conflict",
                        "The draft of '" + code + "' is at rowVersion " + draft.getRowVersion() + ", not " + ifMatch
                                + ".").with("currentRowVersion", draft.getRowVersion());
            }
            draft.replace(content, sha256(content), now, actor);
        }
        if (request.description() != null) {
            pipeline.describe(request.description(), now, actor);
        }
        draft = definitions.saveAndFlush(draft);
        logSaved("saved", draft, definition, actor);
        return response(draft, null, validate(definition));
    }

    @Transactional(readOnly = true)
    public DraftResponse get(String code) {
        pipeline(code);
        PipelineDefinitionEntity draft = draft(code);
        return response(draft, read(draft.getContent()), null);
    }

    @Transactional
    public void delete(String code, String actor) {
        pipeline(code);
        PipelineDefinitionEntity draft = draft(code);
        definitions.delete(draft);
        definitions.flush();
        log.info("draft deleted code={} actor={} rowVersion={}", code, actor, draft.getRowVersion());
    }

    @Transactional(readOnly = true)
    public List<PipelineSummary> list() {
        return pipelines.summaries().stream().map(r -> new PipelineSummary(r.getCode(), r.getDescription(),
                r.getEnabled(), r.getLatestVersion(), r.getLatestPublishedAt(), r.getDraftUpdatedAt() != null,
                r.getDraftUpdatedAt())).toList();
    }

    private PipelineDefinition bind(DraftRequest request, String code) {
        DefinitionBinder.Result result = binder.bind(request.definition(), POINTER);
        if (!result.ok()) {
            throw invalid(result.errors());
        }
        PipelineDefinition definition = result.definition();
        if (!code.equals(definition.code())) {
            throw new ValidationFailedException(List.of(new FieldError(POINTER + "/code",
                    "must equal the pipeline code '" + code + "'")));
        }
        return definition;
    }

    private ValidationReport validate(PipelineDefinition definition) {
        return validator.validate(definition, EndpointStates.of(endpoints));
    }

    private static ValidationFailedException invalid(List<DefinitionIssue> issues) {
        return new ValidationFailedException(issues.stream().map(i -> new FieldError(i.path(), i.message())).toList());
    }

    private PipelineEntity pipeline(String code) {
        return pipelines.findById(code).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "pipeline-not-found", "Pipeline '" + code + "' does not exist."));
    }

    private PipelineDefinitionEntity draft(String code) {
        return definitions.findByCodeAndVersion(code, PipelineDefinitionEntity.DRAFT_VERSION)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "not-found",
                        "Pipeline '" + code + "' has no draft."));
    }

    private static String description(DraftRequest request, PipelineDefinition definition) {
        return request.description() != null ? request.description() : definition.description();
    }

    /** The document as sent, with {@code dslVersion} first when it was absent (AC8). */
    static String content(JsonNode definition) {
        JsonNode stored = definition;
        if (!definition.has("dslVersion")) {
            ObjectNode withVersion = DslJsonMapper.get().createObjectNode();
            withVersion.put("dslVersion", 1);
            withVersion.setAll((ObjectNode) definition);
            stored = withVersion;
        }
        try {
            return DslJsonMapper.get().writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise the definition", e);
        }
    }

    private static JsonNode read(String content) {
        try {
            return DslJsonMapper.get().readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored definition is not readable", e);
        }
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DraftResponse response(PipelineDefinitionEntity draft, JsonNode definition, ValidationReport report) {
        return new DraftResponse(draft.getCode(), draft.getVersion(), draft.getStatus(), draft.getRowVersion(),
                draft.getUpdatedAt(), draft.getUpdatedBy(), definition, report);
    }

    private static void logSaved(String what, PipelineDefinitionEntity draft, PipelineDefinition definition,
            String actor) {
        log.info("draft {} code={} actor={} rowVersion={} steps={}", what, draft.getCode(), actor,
                draft.getRowVersion(), definition.steps().stream().map(StepDefinition::id).toList());
    }
}
