package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.crisil.apiorch.persistence.SqlDialect.Db;

/** ADR-003 rule 4: the two vendor forms, and detection from the connection. */
class SqlDialectTest {

    private final SqlDialect sqlServer = new SqlDialect(Db.SQLSERVER);
    private final SqlDialect h2 = new SqlDialect(Db.H2);

    @Test
    void lockingSelectUsesTheHintOnSqlServerAndForUpdateOnH2() {
        assertThat(sqlServer.lockingSelect("id", "pipeline_run", "id = :id"))
                .isEqualTo("SELECT id FROM pipeline_run WITH (UPDLOCK, ROWLOCK) WHERE id = :id");
        assertThat(h2.lockingSelect("id", "pipeline_run", "id = :id"))
                .isEqualTo("SELECT id FROM pipeline_run WHERE id = :id FOR UPDATE");
    }

    @Test
    void lockTimeoutIsInBatchOnSqlServerAndPerSessionOnH2() {
        assertThat(sqlServer.withLockTimeout("SELECT 1", 2000)).isEqualTo("SET LOCK_TIMEOUT 2000; SELECT 1");
        assertThat(sqlServer.sessionLockTimeoutSql(2000)).isEmpty();
        assertThat(h2.withLockTimeout("SELECT 1", 2000)).isEqualTo("SELECT 1");
        assertThat(h2.sessionLockTimeoutSql(2000)).hasValue("SET LOCK_TIMEOUT 2000");
    }

    @Test
    void detectsTheDatabaseFromTheConnection() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:dialect;MODE=MSSQLServer", "sa", "");
        assertThat(new SqlDialect(ds).db()).isEqualTo(Db.H2);
        assertThat(SqlDialect.fromProductName("Microsoft SQL Server")).isEqualTo(Db.SQLSERVER);
        assertThat(SqlDialect.fromProductName(null)).isEqualTo(Db.SQLSERVER);
    }

    @Test
    void detectionFailureIsADataAccessError() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("down"));
        assertThatThrownBy(() -> new SqlDialect(ds).db()).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
