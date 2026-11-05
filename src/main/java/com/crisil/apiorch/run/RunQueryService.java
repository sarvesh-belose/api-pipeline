package com.crisil.apiorch.run;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.run.dto.RunResource;
import com.crisil.apiorch.security.Roles;

/**
 * Reads one run for {@code GET /api/v1/runs/{runId}} (US-4.2). A caller sees only the runs it created; operators see
 * all. A run the caller may not see is reported exactly like a missing one, so run IDs can't be probed (AC4). State
 * always comes from the database, so a poll on any node returns the latest committed state (AC6).
 */
@Service
public class RunQueryService {

    private final RunRows rows;
    private final RunResourceAssembler assembler;

    public RunQueryService(RunRows rows, RunResourceAssembler assembler) {
        this.rows = rows;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public RunResource get(String runId, Authentication caller) {
        UUID id = parse(runId).orElseThrow(() -> RunResourceAssembler.notFound(runId));
        RunRows.Run run = rows.run(id)
                .filter(r -> isOps(caller) || r.createdBy().equals(caller.getName()))
                .orElseThrow(() -> RunResourceAssembler.notFound(runId));
        return assembler.assemble(run);
    }

    private static boolean isOps(Authentication caller) {
        return caller.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + Roles.OPS));
    }

    private static Optional<UUID> parse(String runId) {
        try {
            UUID id = UUID.fromString(runId);
            return id.toString().equalsIgnoreCase(runId) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
