package com.crisil.apiorch.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.persistence.RunRows;

/**
 * Wakes sync waiters (US-4.1). Each waiter registers its own future <b>before</b> the run is handed to the
 * coordinator, so a run that finishes in microseconds cannot be missed (AC4). The coordinator calls
 * {@link #notifyDone} after the finalizing commit. Runs driven by another node are caught by one shared poll per
 * node, not one per waiter; a reaper drops anything left behind.
 */
@Component
public class RunCompletionNotifier {

    private static final Logger log = LoggerFactory.getLogger(RunCompletionNotifier.class);

    private record Waiter(CompletableFuture<Boolean> future, Instant registered) {
    }

    private final Map<UUID, List<Waiter>> waiters = new ConcurrentHashMap<>();
    private final RunRows rows;
    private final Duration maxAge;
    private final Clock clock;

    public RunCompletionNotifier(RunRows rows, ApiorchProperties properties, Clock clock) {
        this.rows = rows;
        this.maxAge = Duration.ofMillis(properties.run().maxSyncWaitMs()).plusMinutes(1);
        this.clock = clock;
    }

    /**
     * A future that completes with {@code true} when the run is done for the caller, or {@code false} after
     * {@code wait}. The entry is removed either way.
     */
    public CompletableFuture<Boolean> register(UUID runId, Duration wait) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Waiter waiter = new Waiter(future, Instant.now(clock));
        waiters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(waiter);
        future.completeOnTimeout(false, wait.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((done, error) -> remove(runId, waiter));
        return future;
    }

    public void notifyDone(UUID runId) {
        List<Waiter> list = waiters.remove(runId);
        if (list != null) {
            list.forEach(w -> w.future().complete(true));
        }
    }

    /** Releases a waiter whose request failed before the hand-off. */
    public void cancel(UUID runId) {
        List<Waiter> list = waiters.remove(runId);
        if (list != null) {
            list.forEach(w -> w.future().complete(false));
        }
    }

    int waiting() {
        return waiters.size();
    }

    private void remove(UUID runId, Waiter waiter) {
        waiters.computeIfPresent(runId, (id, list) -> {
            list.remove(waiter);
            return list.isEmpty() ? null : list;
        });
    }

    /** One query per interval for all locally waiting runs, for runs finished by another node. */
    @Scheduled(fixedDelay = 500, initialDelay = 500)
    void poll() {
        if (waiters.isEmpty()) {
            return;
        }
        try {
            rows.statuses(waiters.keySet()).forEach((id, status) -> {
                if (status.doneForCaller()) {
                    notifyDone(id);
                }
            });
        } catch (RuntimeException e) {
            log.warn("sync waiter poll failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Backstop: no entry outlives {@code max-sync-wait-ms + 1 min}. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void reap() {
        reapOlderThan(Instant.now(clock).minus(maxAge));
    }

    void reapOlderThan(Instant cutoff) {
        waiters.forEach((id, list) -> list.stream().filter(w -> w.registered().isBefore(cutoff))
                .forEach(w -> w.future().complete(false)));
    }
}
