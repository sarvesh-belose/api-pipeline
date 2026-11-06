package com.crisil.apiorch.run;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** A started run and, for sync mode, the waiter's future; {@code replayed} for an idempotent replay (US-4.7). */
    public record Started(UUID runId, int version, CompletableFuture<Boolean> done, boolean replayed) {
    }

    private final PublishedPipelines pipelines;
    private final InputValidator inputValidator;
    private final RunCompletionNotifier notifier;
    private final RunRows rows;
    private final JdbcRunLeaseService leases;
    private final ActivityRecorder recorder;
    private final RunCoordinator coordinator;
    private final CallerExchangeLog exchanges;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final ApiorchProperties.Run settings;
    private volatile boolean draining;

    public RunService(PublishedPipelines pipelines, InputValidator inputValidator, RunCompletionNotifier notifier,
            RunRows rows, JdbcRunLeaseService leases, ActivityRecorder recorder, RunCoordinator coordinator,
            CallerExchangeLog exchanges, IdempotencyService idempotency, TransactionTemplate tx,
            ApiorchProperties properties) {
        this.pipelines = pipelines;
        this.inputValidator = inputValidator;
        this.notifier = notifier;
        this.rows = rows;
        this.leases = leases;
        this.recorder = recorder;
        this.coordinator = coordinator;
        this.exchanges = exchanges;
        this.idempotency = idempotency;
        this.tx = tx;
        this.settings = properties.run();
    }

    /** The caller's request as received, for the exchange log (US-7.7); {@code bytes} is the true body size. */
    public record Inbound(Map<String, String> headers, long bytes) {
    }

    public Started start(String code, Integer requestedVersion, JsonNode input, boolean sync, String correlationId,
            Inbound inbound, String idempotencyKey, Authentication caller) {
        if (draining) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "shutting-down",
                    "This node is shutting down; retry on another node.");
        }
        String key = IdempotencyService.validate(idempotencyKey);
        String hash = key == null ? null : IdempotencyService.hash(input);
        if (key != null) {
            Optional<IdempotencyService.Existing> existing = idempotency.find(code, caller.getName(), key);
            if (existing.isPresent()) {
                return replay(idempotency.replayOrReject(existing.get(), hash, key), code, sync);
            }
        }
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
                        leases.leaseMs(), key, hash);
                for (CompiledStep step : pipeline.steps()) {
                    rows.insertStep(runId, step.id(), step.definition().effectiveMode().name());
                }
                rows.putContext(runId, "input", input);
                recorder.record(runId, EventType.RUN_ACCEPTED, null, Map.of("mode", mode, "version", version,
                        "inputBytes", inbound.bytes()), null);
                exchanges.inbound(runId, code, version, inbound.headers(), input, inbound.bytes());
            });
        } catch (DataIntegrityViolationException e) {
            notifier.cancel(runId);
            Optional<IdempotencyService.Existing> winner = key == null ? Optional.empty()
                    : idempotency.find(code, caller.getName(), key);
            if (winner.isEmpty()) {
                throw e;
            }
            // AC5: a concurrent request with the same key created the run first
            return replay(idempotency.replayOrReject(winner.get(), hash, key), code, sync);
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
        return new Started(runId, version, done, false);
    }

    /**
     * The existing run for a replay (AC1, AC2, AC6): sync mode waits on it like the original request, finishing at
     * once when it is already done for the caller.
     */
    private Started replay(IdempotencyService.Existing existing, String code, boolean sync) {
        UUID runId = existing.runId();
        if (!sync) {
            return new Started(runId, existing.version(), CompletableFuture.completedFuture(false), true);
        }
        CompletableFuture<Boolean> done = notifier.register(runId,
                syncWait(pipelines.compiled(code, existing.version())));
        if (rows.run(runId).map(r -> r.status().doneForCaller()).orElse(true)) {
            notifier.notifyDone(runId);
        }
        return new Started(runId, existing.version(), done, true);
    }

    /** Graceful shutdown has begun: new runs get {@code 503 shutting-down} (US-5.5 AC7). */
    public void drain() {
        draining = true;
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
