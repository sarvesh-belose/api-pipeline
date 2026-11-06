package com.crisil.apiorch.run;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

/**
 * The {@code apiorch-retry} scheduler (US-5.2): two threads that only wait out a backoff and hand the next attempt
 * to {@code step-io}. Attempts never run here and no thread sleeps, so steps in backoff hold nothing (AC7). A
 * retry lost to a restart is not a problem: the step stays {@code RUNNING} and recovery (US-5.5) decides.
 */
@Component
public class RetryTimers {

    private final AtomicInteger threads = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "apiorch-retry-" + threads.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public void schedule(long delayMs, Runnable handOff) {
        scheduler.schedule(handOff, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
