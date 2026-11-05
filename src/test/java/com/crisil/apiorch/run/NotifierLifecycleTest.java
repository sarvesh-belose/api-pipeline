package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.crisil.apiorch.config.ApiorchProperties;
import com.crisil.apiorch.engine.RunStatus;
import com.crisil.apiorch.persistence.RunRows;

import testsupport.time.MutableClock;

/** US-4.1: no waiter outlives completion, timeout, cancellation or the reaper. */
class NotifierLifecycleTest {

    private final RunRows rows = mock(RunRows.class);
    private final MutableClock clock = MutableClock.at("2026-11-16T09:00:00Z");
    private final RunCompletionNotifier notifier = new RunCompletionNotifier(rows,
            new Binder(new MapConfigurationPropertySource()).bindOrCreate("apiorch", ApiorchProperties.class), clock);

    @Test
    void completionWakesEveryWaiterAndClearsTheEntry() throws Exception {
        UUID run = UUID.randomUUID();
        CompletableFuture<Boolean> a = notifier.register(run, Duration.ofSeconds(30));
        CompletableFuture<Boolean> b = notifier.register(run, Duration.ofSeconds(30));
        notifier.notifyDone(run);
        assertThat(a.get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(b.get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(notifier.waiting()).isZero();
        notifier.notifyDone(UUID.randomUUID());
    }

    @Test
    void timeoutAnswersFalseAndClears() throws Exception {
        CompletableFuture<Boolean> f = notifier.register(UUID.randomUUID(), Duration.ofMillis(50));
        assertThat(f.get(2, TimeUnit.SECONDS)).isFalse();
        Thread.sleep(50);
        assertThat(notifier.waiting()).isZero();
    }

    @Test
    void cancelAndReaperClear() throws Exception {
        UUID run = UUID.randomUUID();
        CompletableFuture<Boolean> f = notifier.register(run, Duration.ofSeconds(30));
        notifier.cancel(run);
        assertThat(f.get(1, TimeUnit.SECONDS)).isFalse();
        notifier.cancel(run);

        CompletableFuture<Boolean> stale = notifier.register(UUID.randomUUID(), Duration.ofMinutes(5));
        notifier.reapOlderThan(Instant.parse("2026-11-16T10:00:00Z"));
        assertThat(stale.get(1, TimeUnit.SECONDS)).isFalse();
        clock.advance(Duration.ofHours(1));
        notifier.reap();
        assertThat(notifier.waiting()).isZero();
    }

    @Test
    void sharedPollWakesRunsFinishedElsewhere() throws Exception {
        UUID done = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        when(rows.statuses(any())).thenReturn(Map.of(done, RunStatus.COMPLETED, running, RunStatus.RUNNING));
        CompletableFuture<Boolean> a = notifier.register(done, Duration.ofSeconds(30));
        CompletableFuture<Boolean> b = notifier.register(running, Duration.ofSeconds(30));
        notifier.poll();
        assertThat(a.get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(b).isNotDone();
        notifier.cancel(running);

        when(rows.statuses(any())).thenThrow(new IllegalStateException("db down"));
        notifier.register(UUID.randomUUID(), Duration.ofMillis(100));
        notifier.poll();
        new RunCompletionNotifier(rows, new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("apiorch", ApiorchProperties.class), clock).poll();
    }
}
