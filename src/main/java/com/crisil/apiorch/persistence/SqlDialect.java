package com.crisil.apiorch.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * The only place SQL differs between SQL Server and H2 in MSSQL mode (ADR-003 rule 4): the row lock and the lock
 * wait. Every other statement is one SQL Server text that runs on both. Nothing outside this class branches on the
 * database (ArchUnit, US-1.1 AC11).
 */
@Component
public class SqlDialect {

    public enum Db { H2, SQLSERVER }

    private final DataSource dataSource;
    private volatile Db db;

    @Autowired
    public SqlDialect(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    SqlDialect(Db db) {
        this.dataSource = null;
        this.db = db;
    }

    /** Detected on first use, so the application starts while the database is down (US-1.1 AC3). */
    public Db db() {
        Db detected = db;
        if (detected == null) {
            // the transaction's own connection when there is one: a second one per caller can exhaust the pool when
            // many first transactions detect at once
            Connection c = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try {
                detected = fromProductName(c.getMetaData().getDatabaseProductName());
            } catch (SQLException e) {
                throw new DataAccessResourceFailureException("Cannot detect the database", e);
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(c, dataSource);
            }
            db = detected;
        }
        return detected;
    }

    static Db fromProductName(String product) {
        return product != null && product.toLowerCase().contains("h2") ? Db.H2 : Db.SQLSERVER;
    }

    /**
     * A row-locking read of {@code table} with {@code where} (README §5.1 rule 1). SQL Server takes
     * {@code WITH (UPDLOCK, ROWLOCK)} and rejects {@code FOR UPDATE} outside a cursor (error 1003); H2 parses the
     * hint but ignores it, so it needs {@code FOR UPDATE} (ADR-003 F3, F4).
     */
    public String lockingSelect(String columns, String table, String where) {
        return db() == Db.SQLSERVER
                ? "SELECT " + columns + " FROM " + table + " WITH (UPDLOCK, ROWLOCK) WHERE " + where
                : "SELECT " + columns + " FROM " + table + " WHERE " + where + " FOR UPDATE";
    }

    /**
     * {@code sql} with a bounded lock wait on SQL Server. mssql-jdbc runs prepared statements through
     * {@code sp_executesql}, where a separate {@code SET LOCK_TIMEOUT} is reverted on return, so it must be in the
     * same batch (ADR-003 F5). H2 uses {@link #sessionLockTimeoutSql} instead.
     */
    public String withLockTimeout(String sql, long millis) {
        return db() == Db.SQLSERVER ? "SET LOCK_TIMEOUT " + millis + "; " + sql : sql;
    }

    /** The per-session lock wait for H2; empty on SQL Server, which uses {@link #withLockTimeout}. */
    public Optional<String> sessionLockTimeoutSql(long millis) {
        return db() == Db.H2 ? Optional.of("SET LOCK_TIMEOUT " + millis) : Optional.empty();
    }
}
