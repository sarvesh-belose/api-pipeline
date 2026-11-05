package com.crisil.apiorch.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.definition.DefinitionBinder;
import com.crisil.apiorch.definition.DefinitionDiff;
import com.crisil.apiorch.definition.DefinitionValidator;
import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.definition.ValidationReport;
import com.crisil.apiorch.endpoint.EndpointRepository;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.persistence.SqlDialect;
import com.crisil.apiorch.pipeline.dto.VersionResponse;
import com.crisil.apiorch.pipeline.dto.VersionSummary;

/**
 * Publishes the draft as the next immutable version (US-3.3), and reads published versions back. The whole publish
 * runs under the {@code pipeline} row lock for the code, which serializes concurrent publishes (and US-3.4
 * rollbacks) of one pipeline. The checksum covers the canonical form, so a reordered {@code steps[]} is a change.
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final JdbcClient jdbc;
    private final SqlDialect dialect;
    private final EndpointRepository endpoints;
    private final DefinitionBinder binder;
    private final DefinitionValidator validator;
    private final ApplicationEventPublisher events;
    private final long lockTimeoutMs;
    private final boolean requireFourEyes;

    public PublishService(JdbcClient jdbc, SqlDialect dialect, EndpointRepository endpoints, Validator beanValidator,
            DefinitionValidator validator, ApplicationEventPublisher events, ApiorchProperties properties) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.endpoints = endpoints;
        this.binder = new DefinitionBinder(beanValidator, properties.definition().maxSteps());
        this.validator = validator;
        this.events = events;
        this.lockTimeoutMs = properties.engine().lockTimeoutMs();
        this.requireFourEyes = properties.definition().requireFourEyes();
    }

    private record Draft(String content, String createdBy, String updatedBy) {
    }

    @Transactional
    public VersionResponse publish(String code, String comment, String actor) {
        LocalDateTime requestStart = jdbc.sql("SELECT SYSUTCDATETIME()").query(LocalDateTime.class).single();
        boolean draftSeen = draftExists(code);
        lockPipeline(code);
        Draft draft = jdbc.sql("SELECT content, created_by, updated_by FROM pipeline_definition "
                        + "WHERE code = :c AND version = 0").param("c", code)
                .query((rs, i) -> new Draft(rs.getString("content"), rs.getString("created_by"),
                        rs.getString("updated_by")))
                .optional().orElseThrow(() -> missingDraft(code, draftSeen, requestStart));
        if (requireFourEyes && actor.equals(draft.updatedBy())) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "forbidden",
                    "four-eyes: a different administrator must publish");
        }
        VersionResponse published = store(code, draft, comment, actor, null, "The draft");
        jdbc.sql("DELETE FROM pipeline_definition WHERE code = :c AND version = 0").param("c", code).update();
        log.info("pipeline published code={} version={} checksum={} actor={} comment={} executionPlan={}", code,
                published.version(), published.checksum(), actor, comment, published.executionPlan());
        return published;
    }

    /**
     * Republishes version {@code to} as the next version (US-3.4), after validating it against today's endpoints.
     * History is never rewritten, the draft is left alone, and four-eyes does not apply: rollback is the emergency
     * path, logged with the actor.
     */
    @Transactional
    public VersionResponse rollback(String code, int to, String comment, String actor) {
        lockPipeline(code);
        VersionSummary source = to < 1 ? null : version(code, to).orElse(null);
        if (source == null) {
            throw notFound(code, String.valueOf(to));
        }
        int latest = versions(code).get(0).version();
        if (latest == to) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "no-changes",
                    "Version " + to + " of '" + code + "' is already the latest.").with("version", to);
        }
        Draft content = jdbc.sql("SELECT content, created_by, updated_by FROM pipeline_definition "
                        + "WHERE code = :c AND version = :v").param("c", code).param("v", to)
                .query((rs, i) -> new Draft(rs.getString("content"), actor, actor)).single();
        VersionResponse published = store(code, content, comment, actor, source, "Version " + to);
        log.info("pipeline rolled back code={} fromVersion={} newVersion={} actor={} comment={}", code, to,
                published.version(), actor, comment);
        return published;
    }

    /** Validates {@code draft}'s content and inserts it as the next version; the caller holds the row lock. */
    private VersionResponse store(String code, Draft draft, String comment, String actor, VersionSummary source,
            String what) {
        JsonNode document = read(draft.content());
        DefinitionBinder.Result bound = binder.bind(document, "");
        ValidationReport report = bound.ok()
                ? validator.validate(bound.definition(), EndpointStates.of(endpoints))
                : ValidationReport.invalid(bound.errors());
        if (!report.valid()) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "definition-invalid",
                    what + " of '" + code + "' has validation errors; nothing was published.")
                    .with("errors", report.errors());
        }
        String checksum = Canonical.checksum(document);
        Optional<VersionSummary> latest = versions(code).stream().findFirst();
        if (latest.isPresent() && latest.get().checksum().equals(checksum)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "no-changes",
                    what + " equals version " + latest.get().version() + " of '" + code + "'.")
                    .with("version", latest.get().version());
        }
        int version = latest.map(v -> v.version() + 1).orElse(1);
        jdbc.sql("""
                INSERT INTO pipeline_definition (id, code, version, status, content, checksum, comment,
                                                 source_version, source_checksum, created_by, updated_by,
                                                 published_at, published_by)
                VALUES (:id, :c, :v, 'PUBLISHED', :content, :sum, :comment, :sourceVersion, :sourceChecksum,
                        :createdBy, :updatedBy, SYSUTCDATETIME(), :actor)""")
                .param("id", UUID.randomUUID()).param("c", code).param("v", version)
                .param("content", draft.content()).param("sum", checksum).param("comment", comment)
                .param("sourceVersion", source == null ? null : source.version())
                .param("sourceChecksum", source == null ? null : source.checksum())
                .param("createdBy", draft.createdBy()).param("updatedBy", draft.updatedBy()).param("actor", actor)
                .update();
        events.publishEvent(new PipelinePublishedEvent(code, version));
        VersionSummary stored = version(code, version).orElseThrow();
        return new VersionResponse(code, version, checksum, stored.publishedAt(), actor, comment,
                report.executionPlan(), null);
    }

    /** The definition document and execution plan of a published version, for the diff (US-3.4). */
    @Transactional(readOnly = true)
    public DefinitionDiff.Side side(String code, int version) {
        requirePipeline(code);
        String content = jdbc.sql("SELECT content FROM pipeline_definition WHERE code = :c AND version = :v")
                .param("c", code).param("v", version).query(String.class).optional()
                .filter(c -> version > 0)
                .orElseThrow(() -> notFound(code, String.valueOf(version)));
        JsonNode document = read(content);
        DefinitionBinder.Result bound = binder.bind(document, "");
        if (!bound.ok()) {
            throw new IllegalStateException("Published " + code + " v" + version + " no longer binds");
        }
        ValidationReport report = validator.validate(bound.definition(), EndpointStates.of(endpoints));
        return new DefinitionDiff.Side(version, document, bound.definition(), report.executionPlan());
    }

    @Transactional(readOnly = true)
    public List<VersionSummary> list(String code) {
        requirePipeline(code);
        return versions(code);
    }

    /** {@code v} is a version number or {@code latest}. */
    @Transactional(readOnly = true)
    public VersionResponse get(String code, String v) {
        requirePipeline(code);
        int version;
        if ("latest".equals(v)) {
            version = versions(code).stream().findFirst().map(VersionSummary::version).orElseThrow(() ->
                    new ApiProblemException(HttpStatus.NOT_FOUND, "no-published-version",
                            "Pipeline '" + code + "' has no published version."));
        } else {
            try {
                version = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw notFound(code, v);
            }
        }
        if (version < 1) {
            throw notFound(code, v);
        }
        VersionSummary summary = version(code, version).orElseThrow(() -> notFound(code, v));
        String content = jdbc.sql("SELECT content FROM pipeline_definition WHERE code = :c AND version = :v")
                .param("c", code).param("v", version).query(String.class).single();
        return new VersionResponse(code, version, summary.checksum(), summary.publishedAt(), summary.publishedBy(),
                summary.comment(), null, read(content));
    }

    private void lockPipeline(String code) {
        dialect.sessionLockTimeoutSql(lockTimeoutMs).ifPresent(sql -> jdbc.sql(sql).update());
        String sql = dialect.withLockTimeout(dialect.lockingSelect("code", "pipeline", "code = :c"), lockTimeoutMs);
        if (jdbc.sql(sql).param("c", code).query(String.class).list().isEmpty()) {
            throw pipelineNotFound(code);
        }
    }

    private boolean draftExists(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM pipeline_definition WHERE code = :c AND version = 0").param("c", code)
                .query(Integer.class).single() > 0;
    }

    /**
     * A draft that vanished because a concurrent publish took it is a conflict, never a 404 (AC5): it was there when
     * this request looked before waiting for the lock, or a version was published after this request started.
     */
    private ApiProblemException missingDraft(String code, boolean draftSeen, LocalDateTime requestStart) {
        boolean publishedMeanwhile = draftSeen || jdbc.sql("SELECT COUNT(*) FROM pipeline_definition "
                        + "WHERE code = :c AND version > 0 AND published_at >= :start")
                .param("c", code).param("start", Timestamp.valueOf(requestStart)).query(Integer.class).single() > 0;
        if (publishedMeanwhile) {
            return new ApiProblemException(HttpStatus.CONFLICT, "version-conflict",
                    "Another publish of '" + code + "' completed first; its draft is gone.");
        }
        return new ApiProblemException(HttpStatus.NOT_FOUND, "not-found", "Pipeline '" + code + "' has no draft.");
    }

    private void requirePipeline(String code) {
        if (jdbc.sql("SELECT COUNT(*) FROM pipeline WHERE code = :c").param("c", code).query(Integer.class)
                .single() == 0) {
            throw pipelineNotFound(code);
        }
    }

    private List<VersionSummary> versions(String code) {
        return jdbc.sql(SUMMARY + " WHERE code = :c AND version > 0 ORDER BY version DESC").param("c", code)
                .query(PublishService::summary).list();
    }

    private Optional<VersionSummary> version(String code, int version) {
        return jdbc.sql(SUMMARY + " WHERE code = :c AND version = :v").param("c", code).param("v", version)
                .query(PublishService::summary).optional();
    }

    private static final String SUMMARY = "SELECT version, checksum, published_at, published_by, comment, "
            + "source_version, source_checksum FROM pipeline_definition";

    private static VersionSummary summary(ResultSet rs, int row) throws SQLException {
        int sourceVersion = rs.getInt("source_version");
        Integer source = rs.wasNull() ? null : sourceVersion;
        return new VersionSummary(rs.getInt("version"), rs.getString("checksum"), instant(rs, "published_at"),
                rs.getString("published_by"), rs.getString("comment"), source, rs.getString("source_checksum"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private static JsonNode read(String content) {
        try {
            return DslJsonMapper.get().readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored definition is not readable", e);
        }
    }

    private static ApiProblemException pipelineNotFound(String code) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "pipeline-not-found",
                "Pipeline '" + code + "' does not exist.");
    }

    private static ApiProblemException notFound(String code, String v) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "not-found",
                "Pipeline '" + code + "' has no published version " + v + ".");
    }
}
