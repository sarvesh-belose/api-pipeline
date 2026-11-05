package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.crisil.apiorch.engine.Lease;
import com.crisil.apiorch.engine.LeaseLostException;

/**
 * Every statement of {@link JdbcRunLeaseService} on both databases (US-5.5 "LeaseSqlIT"), so a syntax slip fails the
 * build rather than the first recovery: owned creation, fenced lock, claim predicate, renew and release.
 */
abstract class LeaseSqlContract {

    @Autowired JdbcRunLeaseService leases;
    @Autowired RunRows rows;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private UUID ownedRun() {
        UUID id = UUID.randomUUID();
        rows.insertRun(id, "LEASE_P", 1, "SYNC", "c", "t", leases.instanceId(), 30000);
        return id;
    }

    @Test
    void ownedRunLocksAtEpochOneAndNotAtAnyOther() {
        UUID run = ownedRun();
        tx.executeWithoutResult(s -> leases.lockFenced(new Lease(run, leases.instanceId(), 1)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> leases.lockFenced(new Lease(run, leases.instanceId(), 2))))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> leases.lockFenced(new Lease(run, "other-node", 1))))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void releasedWithWakeupIsClaimableAndClaimBumpsTheEpoch() {
        UUID run = ownedRun();
        Lease first = new Lease(run, leases.instanceId(), 1);
        assertThat(leases.claim(run)).isEmpty();
        leases.release(first, true);
        assertThat(leases.claimable(50)).contains(run);
        Lease second = leases.claim(run).orElseThrow();
        assertThat(second.epoch()).isEqualTo(2);
        assertThat(leases.claim(run)).isEmpty();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> leases.lockFenced(first)))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void expiredLeaseIsClaimable() {
        UUID run = UUID.randomUUID();
        rows.insertRun(run, "LEASE_P", 1, "SYNC", "c", "t", "dead-node", -1000);
        assertThat(leases.claimable(50)).contains(run);
        assertThat(leases.claim(run)).isPresent();
    }

    @Test
    void renewAndReleaseAll() {
        UUID run = ownedRun();
        assertThat(leases.renewAll()).contains(run);
        assertThat(leases.releaseAllOwned(true)).isGreaterThanOrEqualTo(1);
        assertThat(leases.renewAll()).doesNotContain(run);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM pipeline_run WHERE id = :id AND wakeup_requested = 1")
                .param("id", run).query(Integer.class).single()).isEqualTo(1);
    }
}
