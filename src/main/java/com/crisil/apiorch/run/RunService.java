package com.crisil.apiorch.run;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.definition.CompiledPipeline;
import com.crisil.apiorch.definition.CompiledStep;
import com.crisil.apiorch.engine.Lease;
import com.crisil.apiorch.observability.ActivityRecorder;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.EventType;
import com.crisil.apiorch.persistence.JdbcRunLeaseService;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.security.Roles;

/**
 * Starts runs (US-4.1, US-4.2): resolves and pins the version, checks the input, registers the sync waiter, creates
 * the run owned by this node in one transaction (README §5.1 rule 6), then hands it to the coordinator after
 * commit. If the hand-off fails the run is released with a wake-up, so recovery (US-5.5) continues it.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    /** A started run and, for sync mode, the waiter's future. */
    public record Started(UUID runId, CompletableFuture<Boolean> done) {
    }

    private final PublishedPipelines pipelines;
    private final InputValidator inputValidator;
    private final RunCompletionNotifier notifier;
    private final RunRows rows;
    private final JdbcRunLeaseService leases;
    private final ActivityRecorder recorder;
    private final RunCoordinator coordinator;
    private final TransactionTemplate tx;
    private final ApiorchProperties.Run settings;

    public RunService(PublishedPipelines pipelines, InputValidator inputValidator, RunCompletionNotifier notifier,
            RunRows rows, JdbcRunLeaseService leases, ActivityRecorder recorder, RunCoordinator coordinator,
            TransactionTemplate tx, ApiorchProperties properties) {
        this.pipelines = pipelines;
        this.inputValidator = inputValidator;
        this.notifier = notifier;
        this.rows = rows;
        this.leases = leases;
        this.recorder = recorder;
        this.coordinator = coordinator;
        this.tx = tx;
        this.settings = properties.run();
    }

    public Started start(String code, Integer requestedVersion, JsonNode input, boolean sync, String correlationId,
            Authentication caller) {
        int version = resolveVersion(code, requestedVersion, caller);
        CompiledPipeline pipeline = pipelines.compiled(code, version);
        inputValidator.check(pipeline, input);

        UUID runId = UUID.randomUUID();
        CompletableFuture<Boolean> done = sync ? notifier.register(runId, syncWait(pipeline))
                : CompletableFuture.completedFuture(false);
        String mode = sync ? "SYNC" : "ASYNC";
        try {
            tx.executeWithoutResult(s -> {
                rows.insertRun(runId, code, version, mode, correlationId, caller.getName(), leases.instanceId(),
                        leases.leaseMs());
                for (CompiledStep step : pipeline.steps()) {
                    rows.insertStep(runId, step.id(), step.definition().effectiveMode().name());
                }
                rows.putContext(runId, "input", input);
                recorder.record(runId, EventType.RUN_ACCEPTED, null, Map.of("mode", mode, "version", version,
                        "inputBytes", input.toString().length()), null);
            });
        } catch (RuntimeException e) {
            notifier.cancel(runId);
            throw e;
        }
        log.info("run accepted runId={} pipeline={} version={} mode={}", runId, code, version, mode);
        Lease lease = new Lease(runId, leases.instanceId(), 1);
        try {
            coordinator.start(lease);
        } catch (RuntimeException e) {
            log.error("run hand-off failed runId={}; released for recovery", runId, e);
            leases.release(lease, true);
        }
        return new Started(runId, done);
    }

    private int resolveVersion(String code, Integer requested, Authentication caller) {
        PublishedPipelines.Latest latest = pipelines.latest(code);
        if (!latest.exists()) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "pipeline-not-found",
                    "Pipeline '" + code + "' does not exist.");
        }
        if (requested != null) {
            boolean admin = caller.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_" + Roles.ADMIN));
            if (!admin || !settings.allowVersionOverride()) {
                throw new ApiProblemException(HttpStatus.FORBIDDEN, "forbidden",
                        "Choosing a version needs apiorch.admin and apiorch.run.allow-version-override.");
            }
            if (!pipelines.versionExists(code, requested)) {
                throw new ApiProblemException(HttpStatus.NOT_FOUND, "no-published-version",
                        "Pipeline '" + code + "' has no published version " + requested + ".");
            }
            return requested;
        }
        if (latest.version() == null) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "no-published-version",
                    "Pipeline '" + code + "' has no published version.");
        }
        return latest.version();
    }

    private Duration syncWait(CompiledPipeline pipeline) {
        Integer declared = pipeline.definition().syncWaitMs();
        long wait = declared != null ? declared : settings.defaultSyncWaitMs();
        return Duration.ofMillis(Math.min(wait, settings.maxSyncWaitMs()));
    }
}
