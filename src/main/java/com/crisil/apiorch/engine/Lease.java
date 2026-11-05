package com.crisil.apiorch.engine;

import java.util.UUID;

/**
 * The right to drive one run: {@code (owner_instance, lease_epoch)} on its {@code pipeline_run} row (README §5.1
 * rule 3). Every coordinator write transaction starts by locking the row with this pair; if no row matches, the
 * lease was lost and the transaction writes nothing.
 */
public record Lease(UUID runId, String owner, long epoch) {
}
