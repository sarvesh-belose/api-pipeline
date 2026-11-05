package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Schema-ahead V2–V7 (R1 plan §7, README §10): every table exists with its keys, and the vendor pairs behave the
 * same on H2 (SchemaIT) and SQL Server (SchemaSqlServerIT).
 */
abstract class SchemaContract {

    static final List<String> TABLES = List.of("shedlock", "endpoint", "pipeline", "pipeline_definition",
            "pipeline_run", "step_execution", "run_context", "run_event", "step_attempt", "run_exchange");

    @Autowired JdbcClient jdbc;
    @Autowired SqlDialect dialect;
    @Autowired TransactionTemplate tx;

    UUID insertRun(String idempotencyKey) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pipeline_run (id, pipeline_code, pipeline_version, status, mode, correlation_id,
                                          idempotency_key, created_by)
                VALUES (:id, 'DEMO_ONBOARD', 1, 'ACCEPTED', 'SYNC', 'c-1', :key, 'caller-a')""")
                .param("id", id).param("key", idempotencyKey).update();
        return id;
    }

    @Test
    void everyTableExists() {
        for (String table : TABLES) {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single())
                    .as(table).isNotNegative();
        }
    }

    @Test
    void runsWithoutAnIdempotencyKeyDoNotCollide() {
        insertRun(null);
        insertRun(null);
        String key = "k-" + UUID.randomUUID();
        insertRun(key);
        assertThatThrownBy(() -> insertRun(key)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void draftIsVersionZeroAndOnlyOnePerCode() {
        String code = "P_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.sql("INSERT INTO pipeline (code, created_by, updated_by) VALUES (:c, 'a', 'a')").param("c", code).update();
        String insert = """
                INSERT INTO pipeline_definition (id, code, version, status, content, checksum, created_by, updated_by)
                VALUES (:id, :c, :v, :s, '{}', '0000000000000000000000000000000000000000000000000000000000000000', 'a', 'a')""";
        jdbc.sql(insert).param("id", UUID.randomUUID()).param("c", code).param("v", 0).param("s", "DRAFT").update();
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", UUID.randomUUID()).param("c", code).param("v", 0)
                .param("s", "DRAFT").update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", UUID.randomUUID()).param("c", code).param("v", 1)
                .param("s", "DRAFT").update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDialectRowLockRunsAgainstPipelineRun() {
        UUID id = insertRun(null);
        UUID locked = tx.execute(s -> jdbc.sql(dialect.lockingSelect("id", "pipeline_run", "id = :id"))
                .param("id", id).query(UUID.class).single());
        assertThat(locked).isEqualTo(id);
    }

    @Test
    void stepRowsBelongToARun() {
        UUID run = insertRun(null);
        jdbc.sql("""
                INSERT INTO step_execution (id, run_id, step_id, status, mode)
                VALUES (:id, :run, 'api1', 'PENDING', 'SYNC')""")
                .param("id", UUID.randomUUID()).param("run", run).update();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO step_execution (id, run_id, step_id, status, mode)
                VALUES (:id, :run, 'api1', 'PENDING', 'SYNC')""")
                .param("id", UUID.randomUUID()).param("run", run).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
