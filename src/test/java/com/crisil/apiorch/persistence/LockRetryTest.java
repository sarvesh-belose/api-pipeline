package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

/** Lock contention is retried (SQL Server 1205 and 1222, H2 50200), anything else is not. */
class LockRetryTest {

    private static RuntimeException timeout(int code) {
        return new UncategorizedSQLException("lockFenced", "SELECT", new SQLException("lock", "S0001", code));
    }

    @Test
    void lockFailuresAreRecognisedThroughTheCauseChain() {
        assertThat(LockRetry.isLockFailure(timeout(1222))).isTrue();
        assertThat(LockRetry.isLockFailure(timeout(1205))).isTrue();
        assertThat(LockRetry.isLockFailure(timeout(50200))).isTrue();
        assertThat(LockRetry.isLockFailure(new CannotAcquireLockException("deadlock"))).isTrue();
        assertThat(LockRetry.isLockFailure(timeout(2627))).isFalse();
        assertThat(LockRetry.isLockFailure(new DataIntegrityViolationException("dup"))).isFalse();
    }

    @Test
    void aLockFailureIsRetriedUntilItSucceedsOrRunsOut() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(LockRetry.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw timeout(1222);
            }
            return "ok";
        })).isEqualTo("ok");
        assertThat(calls).hasValue(3);

        calls.set(0);
        assertThatThrownBy(() -> LockRetry.run(() -> {
            calls.incrementAndGet();
            throw timeout(1222);
        })).isInstanceOf(UncategorizedSQLException.class);
        assertThat(calls).hasValue(LockRetry.ATTEMPTS);

        calls.set(0);
        assertThatThrownBy(() -> LockRetry.run(() -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("dup");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(1);
    }
}
