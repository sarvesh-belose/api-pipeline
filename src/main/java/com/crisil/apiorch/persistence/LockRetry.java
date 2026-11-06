package com.crisil.apiorch.persistence;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Repeats a whole transaction that lost to lock contention (README §5.1): a SQL Server deadlock victim (1205) or a
 * bounded lock wait that ran out ({@code SET LOCK_TIMEOUT}, error 1222; H2 50200, ADR-003 F5). The transaction rolled
 * back whole, so running it again is safe. mssql-jdbc reports 1222 as an uncategorized SQL exception, so the cause
 * chain is checked as well as Spring's lock exceptions.
 */
public final class LockRetry {

    private static final Logger log = LoggerFactory.getLogger(LockRetry.class);

    public static final int ATTEMPTS = 5;

    private LockRetry() {
    }

    public static <T> T run(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (RuntimeException e) {
                if (!isLockFailure(e) || attempt >= ATTEMPTS) {
                    throw e;
                }
                log.warn("transaction retried after {} attempt={}", e.getClass().getSimpleName(), attempt);
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** A deadlock or a lock wait timeout, anywhere in the cause chain. */
    public static boolean isLockFailure(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (c instanceof SQLException sql && (sql.getErrorCode() == 1222 || sql.getErrorCode() == 1205
                    || sql.getErrorCode() == 50200 || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
