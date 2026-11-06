package com.crisil.apiorch.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.config.InstanceIdentity;
import com.crisil.apiorch.engine.Lease;
import com.crisil.apiorch.engine.LeaseLostException;
import com.crisil.apiorch.engine.RunLeaseService;

/**
 * {@link RunLeaseService} on {@code pipeline_run} (US-5.5, README §5.1). One SQL Server text for both databases
 * except the row lock and lock wait, which come from {@link SqlDialect}. Lease times are database time.
 */
@Component
public class JdbcRunLeaseService implements RunLeaseService {

    static final String CLAIM_PREDICATE = "((owner_instance IS NULL AND wakeup_requested = 1)"
            + " OR (owner_instance IS NOT NULL AND lease_until < SYSUTCDATETIME()))";

    private final JdbcClient jdbc;
    private final SqlDialect dialect;
    private final String me;
    private final long leaseMs;
    private final long lockTimeoutMs;

    public JdbcRunLeaseService(JdbcClient jdbc, SqlDialect dialect, InstanceIdentity instance,
            ApiorchProperties properties) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.me = instance.id();
        this.leaseMs = properties.lease().durationMs();
        this.lockTimeoutMs = properties.engine().lockTimeoutMs();
    }

    public String instanceId() {
        return me;
    }

    public long leaseMs() {
        return leaseMs;
    }

    @Override
    @Transactional
    public Optional<Lease> claim(UUID runId) {
        int updated = jdbc.sql("""
                UPDATE pipeline_run SET owner_instance = :me, lease_epoch = lease_epoch + 1,
                    lease_until = DATEADD(MILLISECOND, :leaseMs, SYSUTCDATETIME()), wakeup_requested = 0
                WHERE id = :id AND """ + CLAIM_PREDICATE)
                .param("me", me).param("leaseMs", leaseMs).param("id", runId).update();
        if (updated != 1) {
            return Optional.empty();
        }
        long epoch = jdbc.sql("SELECT lease_epoch FROM pipeline_run WHERE id = :id").param("id", runId)
                .query(Long.class).single();
        return Optional.of(new Lease(runId, me, epoch));
    }

    @Override
    public List<UUID> claimable(int limit) {
        return jdbc.sql("SELECT TOP (" + Math.max(1, limit) + ") id FROM pipeline_run WHERE " + CLAIM_PREDICATE
                + " ORDER BY lease_until").query(UUID.class).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockFenced(Lease lease) {
        dialect.sessionLockTimeoutSql(lockTimeoutMs).ifPresent(sql -> jdbc.sql(sql).update());
        String sql = dialect.withLockTimeout(dialect.lockingSelect("id", "pipeline_run",
                "id = :id AND owner_instance = :me AND lease_epoch = :epoch"), lockTimeoutMs);
        List<UUID> rows = jdbc.sql(sql).param("id", lease.runId()).param("me", lease.owner())
                .param("epoch", lease.epoch()).query(UUID.class).list();
        if (rows.isEmpty()) {
            throw new LeaseLostException(lease);
        }
    }

    /**
     * Locks the run row without a lease, for writers that don't own the run (the caller exchange, US-7.7), so their
     * events still take the next {@code seq} in order (README §5.1 rule 1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUnowned(UUID runId) {
        dialect.sessionLockTimeoutSql(lockTimeoutMs).ifPresent(sql -> jdbc.sql(sql).update());
        String sql = dialect.withLockTimeout(dialect.lockingSelect("id", "pipeline_run", "id = :id"), lockTimeoutMs);
        if (jdbc.sql(sql).param("id", runId).query(UUID.class).list().isEmpty()) {
            throw new IllegalStateException("Run " + runId + " does not exist");
        }
    }

    @Override
    @Transactional
    public Set<UUID> renewAll() {
        jdbc.sql("UPDATE pipeline_run SET lease_until = DATEADD(MILLISECOND, :leaseMs, SYSUTCDATETIME()) "
                + "WHERE owner_instance = :me").param("leaseMs", leaseMs).param("me", me).update();
        return new HashSet<>(jdbc.sql("SELECT id FROM pipeline_run WHERE owner_instance = :me").param("me", me)
                .query(UUID.class).list());
    }

    @Override
    @Transactional
    public void release(Lease lease, boolean wakeup) {
        jdbc.sql("""
                UPDATE pipeline_run SET owner_instance = NULL, lease_until = NULL, wakeup_requested = :wakeup
                WHERE id = :id AND owner_instance = :me AND lease_epoch = :epoch""")
                .param("wakeup", wakeup ? 1 : 0).param("id", lease.runId()).param("me", lease.owner())
                .param("epoch", lease.epoch()).update();
    }

    @Override
    @Transactional
    public int releaseAllOwned(boolean wakeup) {
        return jdbc.sql("UPDATE pipeline_run SET owner_instance = NULL, lease_until = NULL, wakeup_requested = :wakeup "
                + "WHERE owner_instance = :me").param("wakeup", wakeup ? 1 : 0).param("me", me).update();
    }
}
