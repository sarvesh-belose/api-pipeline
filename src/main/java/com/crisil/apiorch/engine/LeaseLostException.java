package com.crisil.apiorch.engine;

/**
 * The fenced row lock found no row for this node's {@link Lease}: another node has claimed the run (README §5.1
 * rule 3). The node drops the run locally, writes nothing and discards late results.
 */
public class LeaseLostException extends RuntimeException {

    private final Lease lease;

    public LeaseLostException(Lease lease) {
        super("Lease lost for run " + lease.runId() + " at epoch " + lease.epoch());
        this.lease = lease;
    }

    public Lease lease() {
        return lease;
    }
}
