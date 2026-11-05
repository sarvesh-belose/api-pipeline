package com.crisil.apiorch.engine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Run ownership (US-5.5, README §5.1 rules 3–6). All lease arithmetic uses database time
 * ({@code SYSUTCDATETIME()}), never the JVM clock. The JDBC implementation lives outside {@code engine}
 * ({@code persistence}), because engine code stays free of JDBC (README §13).
 */
public interface RunLeaseService {

    /**
     * Claims a run that needs a driver: unowned with {@code wakeup_requested = 1}, or owned with an expired
     * lease (the only claim predicate, rule 5). Sets the owner, bumps the epoch, extends the lease and clears the
     * wake-up flag. Empty when another node won or the run no longer qualifies.
     */
    Optional<Lease> claim(UUID runId);

    /** Up to {@code limit} run ids matching the claim predicate, oldest lease first (the recovery sweeper). */
    List<UUID> claimable(int limit);

    /**
     * Locks the run row for this lease inside the caller's transaction, with the bounded lock wait
     * ({@code apiorch.engine.lock-timeout-ms}). Every coordinator write transaction starts with it (rule 1).
     *
     * @throws LeaseLostException when the row is no longer owned at this epoch
     */
    void lockFenced(Lease lease);

    /**
     * Extends every lease this node holds and returns the run ids it still owns; a run driven locally but missing
     * from the result has been lost.
     */
    Set<UUID> renewAll();

    /** Gives the run up; with {@code wakeup} another node (or this one) will claim and continue it (rule 4). */
    void release(Lease lease, boolean wakeup);

    /** Releases every lease this node holds, for graceful shutdown; returns how many. */
    int releaseAllOwned(boolean wakeup);
}
