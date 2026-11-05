package com.crisil.apiorch.step;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.engine.Lease;

/**
 * Everything an executor needs for one execution of one step (US-4.3).
 *
 * @param lease         the coordinator's lease; attempt rows and events are written under it, so a node that has
 *                      lost the run writes nothing (README §5.1 rule 3)
 * @param execNo        1 for the first execution; incremented by resume (US-5.8)
 * @param context       the read-only expression context snapshot (README §8)
 * @param deadline      the run's segment deadline; each attempt's timeout is capped by the time left (US-5.1)
 */
public record StepInvocation(
        Lease lease,
        String pipeline,
        int version,
        String correlationId,
        CompiledStep step,
        int execNo,
        JsonNode context,
        Instant deadline) {

    public UUID runId() {
        return lease.runId();
    }
}
