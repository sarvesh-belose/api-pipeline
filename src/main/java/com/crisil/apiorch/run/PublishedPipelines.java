package com.crisil.apiorch.run;

import java.time.Duration;
import java.util.Optional;

import jakarta.validation.Validator;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.DefinitionBinder;
import com.crisil.apiorch.definition.DslJsonMapper;
import com.crisil.apiorch.definition.PipelineCompiler;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.pipeline.PipelinePublishedEvent;

/**
 * Published versions ready to run (US-3.3, US-4.1). A version is immutable, so its compiled form is cached for
 * the life of the node; compiling happens once per {@code (code, version)} per node. The latest version is cached
 * for {@code apiorch.definition.latest-cache-ttl-ms}: a publish evicts it here at once, and other nodes converge
 * within the TTL (US-3.3 AC3).
 */
@Component
public class PublishedPipelines {

    /** The pipeline row's existence and its latest published version, if any. */
    public record Latest(boolean exists, boolean enabled, Integer version) {
    }

    private final JdbcClient jdbc;
    private final DefinitionBinder binder;
    private final PipelineCompiler compiler;
    private final Cache<String, CompiledPipeline> cache = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterAccess(Duration.ofHours(12)).build();
    private final Cache<String, Latest> latest;

    public PublishedPipelines(JdbcClient jdbc, Validator validator, ExpressionEvaluator evaluator,
            ApiorchProperties properties) {
        this.jdbc = jdbc;
        this.binder = new DefinitionBinder(validator, properties.definition().maxSteps());
        this.compiler = new PipelineCompiler(evaluator);
        this.latest = Caffeine.newBuilder().maximumSize(1000)
                .expireAfterWrite(Duration.ofMillis(properties.definition().latestCacheTtlMs())).build();
    }

    public Latest latest(String code) {
        return latest.get(code, this::loadLatest);
    }

    @TransactionalEventListener
    void onPublished(PipelinePublishedEvent event) {
        latest.invalidate(event.code());
    }

    private Latest loadLatest(String code) {
        return jdbc.sql("""
                SELECT p.enabled AS enabled,
                       (SELECT MAX(d.version) FROM pipeline_definition d WHERE d.code = p.code AND d.version > 0) AS v
                FROM pipeline p WHERE p.code = :code""").param("code", code)
                .query((rs, i) -> {
                    int v = rs.getInt("v");
                    Integer version = rs.wasNull() ? null : v;
                    return new Latest(true, rs.getBoolean("enabled"), version);
                })
                .optional().orElse(new Latest(false, false, null));
    }

    public boolean versionExists(String code, int version) {
        return version > 0 && jdbc.sql("SELECT COUNT(*) FROM pipeline_definition WHERE code = :c AND version = :v")
                .param("c", code).param("v", version).query(Integer.class).single() > 0;
    }

    public CompiledPipeline compiled(String code, int version) {
        return cache.get(code + "#" + version, key -> load(code, version).orElseThrow(() ->
                new IllegalStateException("Pipeline " + code + " v" + version + " is not published")));
    }

    private Optional<CompiledPipeline> load(String code, int version) {
        return jdbc.sql("SELECT content FROM pipeline_definition WHERE code = :c AND version = :v")
                .param("c", code).param("v", version).query(String.class).optional()
                .map(content -> {
                    try {
                        DefinitionBinder.Result bound = binder.bind(DslJsonMapper.get().readTree(content), "");
                        if (!bound.ok()) {
                            throw new IllegalStateException("Published " + code + " v" + version
                                    + " no longer binds: " + bound.errors());
                        }
                        return compiler.compile(version, bound.definition(), "");
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Published " + code + " v" + version + " is not JSON", e);
                    }
                });
    }
}
