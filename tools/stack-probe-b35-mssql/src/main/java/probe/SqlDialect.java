package probe;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/**
 * The only place engine SQL differs between H2 (MSSQL mode) and SQL Server. Everything else is one statement.
 * The probe records which forms each database accepts; ADR-003 turns that into the rule.
 */
@Component
public class SqlDialect {

    public enum Db { H2, SQLSERVER }

    private final Db db;

    public SqlDialect(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            this.db = product.toLowerCase().contains("h2") ? Db.H2 : Db.SQLSERVER;
        }
    }

    public Db db() {
        return db;
    }

    /** Database UTC "now". */
    public String now() {
        return "SYSUTCDATETIME()";
    }

    /** now + :millis, in database time. */
    public String nowPlus(String millisParam) {
        return "DATEADD(MILLISECOND, :" + millisParam + ", SYSUTCDATETIME())";
    }

    /**
     * Row lock for the fenced read (README §5.1 rules 1 and 3). H2 parses the SQL Server table hint and ignores it,
     * so H2 must use FOR UPDATE; SQL Server rejects FOR UPDATE outside a cursor (error 1003).
     */
    public String lockRunSql() {
        return db == Db.SQLSERVER
                ? "SELECT id FROM pipeline_run WITH (UPDLOCK, ROWLOCK) WHERE id = :id AND owner_instance = :me AND lease_epoch = :e"
                : "SELECT id FROM pipeline_run WHERE id = :id AND owner_instance = :me AND lease_epoch = :e FOR UPDATE";
    }

    /**
     * {@code sql} with a bounded lock wait. mssql-jdbc runs prepared statements through sp_executesql, where a
     * separate SET LOCK_TIMEOUT is reverted on return, so SQL Server needs it in the same batch.
     */
    public String withLockTimeout(String sql, int millis) {
        return db == Db.SQLSERVER ? "SET LOCK_TIMEOUT " + millis + "; " + sql : sql;
    }

    /** Session-level lock wait for H2; empty on SQL Server, which uses {@link #withLockTimeout} instead. */
    public java.util.Optional<String> sessionLockTimeoutSql(int millis) {
        return db == Db.H2 ? java.util.Optional.of("SET LOCK_TIMEOUT " + millis) : java.util.Optional.empty();
    }
}
