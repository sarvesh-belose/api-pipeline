package com.crisil.apiorch.run;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crisil.apiorch.engine.Lease;
import com.crisil.apiorch.engine.RunLeaseService;
import com.crisil.apiorch.persistence.RunRows;

/**
 * Lease renewal and recovery (US-5.5, README §5.1). Every node renews every run it owns, whatever its status, every
 * {@code apiorch.lease.renew-interval-ms}; and every node sweeps every {@code apiorch.recovery.sweep-interval-ms}
 * for runs matching the single claim predicate (an expired lease, or no owner with a wake-up). That is safe on every
 * node because a claim is one atomic {@code UPDATE}: exactly one node wins and the others see zero rows.
 */
@Component
public class RecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);
    static final int BATCH = 50;

    private final RunLeaseService leases;
    private final RunCoordinator coordinator;
    private final RunRows rows;

    public RecoverySweeper(RunLeaseService leases, RunCoordinator coordinator, RunRows rows) {
        this.leases = leases;
        this.coordinator = coordinator;
        this.rows = rows;
    }

    @Scheduled(fixedDelayString = "${apiorch.lease.renew-interval-ms:10000}",
            initialDelayString = "${apiorch.lease.renew-interval-ms:10000}")
    void renew() {
        try {
            Set<UUID> owned = leases.renewAll();
            log.debug("leases renewed owned={}", owned.size());
        } catch (RuntimeException e) {
            log.warn("lease renewal failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${apiorch.recovery.sweep-interval-ms:15000}",
            initialDelayString = "${apiorch.recovery.sweep-interval-ms:15000}")
    void sweep() {
        try {
            for (UUID runId : leases.claimable(BATCH)) {
                String previousOwner = rows.run(runId).map(RunRows.Run::ownerInstance).orElse(null);
                Optional<Lease> lease = leases.claim(runId);
                if (lease.isEmpty()) {
                    continue; // another node won the claim (AC4)
                }
                log.warn("run recovered runId={} previousOwner={} epoch={}", runId, previousOwner, lease.get().epoch());
                coordinator.recover(lease.get(), previousOwner);
            }
        } catch (RuntimeException e) {
            log.warn("recovery sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
