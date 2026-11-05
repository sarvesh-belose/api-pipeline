package testsupport.engine;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.definition.DslJsonMapper;

/**
 * Publishes fixture definitions straight into {@code pipeline_definition}, for engine tests that must not depend on
 * the publish API (US-4.1 "fixture definitions"; US-3.3 owns publishing).
 */
public final class PublishedFixtures {

    private PublishedFixtures() {
    }

    public static String uniqueCode(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Publishes {@code doc} as {@code version} of its code, creating the pipeline row if needed. */
    public static void publish(JdbcClient jdbc, ObjectNode doc, int version) {
        String code = doc.path("code").asText();
        if (jdbc.sql("SELECT COUNT(*) FROM pipeline WHERE code = :c").param("c", code).query(Integer.class).single() == 0) {
            jdbc.sql("INSERT INTO pipeline (code, created_by, updated_by) VALUES (:c, 'fixture', 'fixture')")
                    .param("c", code).update();
        }
        String content;
        try {
            content = DslJsonMapper.get().writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        jdbc.sql("""
                INSERT INTO pipeline_definition (id, code, version, status, content, checksum, created_by, updated_by,
                                                 published_at, published_by)
                VALUES (:id, :c, :v, 'PUBLISHED', :content, :sum, 'fixture', 'fixture', SYSUTCDATETIME(), 'fixture')""")
                .param("id", UUID.randomUUID()).param("c", code).param("v", version).param("content", content)
                .param("sum", "0".repeat(64)).update();
    }
}
