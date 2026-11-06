package com.crisil.apiorch.run;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One timer per running segment on this node (US-5.1), on the {@code apiorch-deadline} thread. When it fires, the
 * coordinator persists {@code stop_reason = DEADLINE}; it is cancelled when the run finishes. A lost timer (crash,
 * lost lease) is not a problem: {@code advance} also checks the deadline, and recovery (US-5.5) finalizes runs found
 * past it.
 */
@Component
public class DeadlineTimers {

    private static final Logger log = LoggerFactory.getLogger(DeadlineTimers.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "apiorch-deadline");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public void schedule(UUID runId, long delayMs, Runnable onDeadline) {
        ScheduledFuture<?> previous = timers.put(runId, scheduler.schedule(() -> {
            timers.remove(runId);
            try {
                onDeadline.run();
            } catch (RuntimeException e) {
                log.error("deadline handling failed runId={}", runId, e);
            }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public void cancel(UUID runId) {
        ScheduledFuture<?> timer = timers.remove(runId);
        if (timer != null) {
            timer.cancel(false);
        }
    }

    int pending() {
        return timers.size();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
