package com.crisil.apiorch.run;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.crisil.apiorch.engine.RunLeaseService;

/**
 * Graceful shutdown (US-5.5 AC7), stopped before the web server (highest phase): readiness turns
 * {@code REFUSING_TRAFFIC}, new runs get {@code 503 shutting-down}, the coordinator starts no new step, in-flight
 * attempts get up to {@code apiorch.shutdown.grace-ms} (20 s) to finish and be recorded, then every lease this node
 * holds is released with a wake-up so another node continues at once. Attempts still running after the grace period
 * are handled by fencing: their late results are discarded and recovery applies the idempotency rule.
 */
@Component
public class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    private final RunCoordinator coordinator;
    private final RunService runs;
    private final RunLeaseService leases;
    private final ApplicationEventPublisher events;
    private final Duration grace;
    private volatile boolean running;

    public GracefulShutdown(RunCoordinator coordinator, RunService runs, RunLeaseService leases,
            ApplicationEventPublisher events,
            @org.springframework.beans.factory.annotation.Value("${apiorch.shutdown.grace-ms:20000}") long graceMs) {
        this.coordinator = coordinator;
        this.runs = runs;
        this.leases = leases;
        this.events = events;
        this.grace = Duration.ofMillis(graceMs);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        runs.drain();
        coordinator.drain();
        boolean idle = coordinator.awaitIdle(grace);
        int released;
        try {
            released = leases.releaseAllOwned(true);
        } catch (RuntimeException e) {
            log.warn("shutdown: lease release failed ({}); leases expire instead", e.getClass().getSimpleName());
            return;
        }
        log.info("shutdown: released {} leases inFlightFinished={}", released, idle);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stopped first, while Tomcat still answers (with 503 for new runs) and in-flight steps can still record. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
