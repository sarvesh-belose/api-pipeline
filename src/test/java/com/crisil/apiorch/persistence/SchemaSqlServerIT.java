package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import testsupport.db.SharedSqlServer;

/**
 * Slow tier: the schema on SQL Server 2022, plus what only SQL Server has — monthly partitions (US-1.1 AC6,
 * ADR-003 F9), ISJSON checks (F8) and the append-only grants (US-7.1 AC7, F10).
 */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class SchemaSqlServerIT extends SchemaContract {

    @Autowired DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings = {"run_event", "step_attempt", "run_exchange"})
    void partitionedTablesPutTheCurrentMonthInItsOwnPartition(String table) {
        String scheme = jdbc.sql("""
                SELECT ps.name FROM sys.indexes i
                JOIN sys.partition_schemes ps ON ps.data_space_id = i.data_space_id
                WHERE i.object_id = OBJECT_ID(:t) AND i.index_id = 1""").param("t", table)
                .query(String.class).single();
        assertThat(scheme).isEqualTo("ps_month");
        int partitions = jdbc.sql("SELECT COUNT(*) FROM sys.partitions WHERE object_id = OBJECT_ID(:t) AND index_id = 1")
                .param("t", table).query(Integer.class).single();
        int current = jdbc.sql("SELECT $PARTITION.pf_month(SYSUTCDATETIME())").query(Integer.class).single();
        assertThat(current).as("not the first (before-range) partition").isGreaterThan(1);
        assertThat(current).as("not the last (open-ended) partition").isLessThan(partitions);
        int nextMonth = jdbc.sql("SELECT $PARTITION.pf_month(DATEADD(MONTH, 1, SYSUTCDATETIME()))")
                .query(Integer.class).single();
        assertThat(nextMonth).isEqualTo(current + 1).isLessThan(partitions);
    }

    @Test
    void jsonColumnsRejectInvalidJson() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO run_event (run_id, seq, event_type, occurred_at, details)
                VALUES (NEWID(), 1, 'RUN_ACCEPTED', SYSUTCDATETIME(), 'not json')""").update())
                .hasMessageContaining("ck_run_event_details_json");
    }

    @Test
    void applicationRoleCannotUpdateOrDeleteAuditRows() throws SQLException {
        UUID run = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("IF USER_ID('probe_app') IS NULL CREATE USER probe_app WITHOUT LOGIN");
            st.execute("ALTER ROLE apiorch_app_role ADD MEMBER probe_app");
            st.execute("EXECUTE AS USER = 'probe_app'");
            try {
                st.executeUpdate("INSERT INTO run_event (run_id, seq, event_type, occurred_at) VALUES ('" + run
                        + "', 1, 'RUN_ACCEPTED', SYSUTCDATETIME())");
                for (String sql : new String[] {
                        "UPDATE run_event SET actor = 'x' WHERE run_id = '" + run + "'",
                        "DELETE FROM run_event WHERE run_id = '" + run + "'",
                        "UPDATE step_attempt SET outcome = 'x'",
                        "DELETE FROM run_exchange"}) {
                    assertThatThrownBy(() -> st.executeUpdate(sql)).as(sql)
                            .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(229));
                }
                assertThat(st.executeUpdate("UPDATE pipeline_run SET status = status WHERE 1 = 0")).isZero();
            } finally {
                st.execute("REVERT");
            }
        }
    }
}
