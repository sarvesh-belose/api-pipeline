package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** US-1.3 AC3: run and step context reaches the worker thread, and neither side leaks it. */
class MdcPropagationTest {

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        MDC.clear();
    }

    private Map<String, String> mdcOnWorker() throws Exception {
        CompletableFuture<Map<String, String>> seen = new CompletableFuture<>();
        executor.execute(() -> seen.complete(MDC.getCopyOfContextMap()));
        return seen.get(5, TimeUnit.SECONDS);
    }

    @Test
    void runAndStepContextReachTheWorker() throws Exception {
        UUID runId = UUID.randomUUID();
        Map<String, String> seen;
        try (MdcSupport.Scope run = MdcSupport.withRun(runId, "DEMO_ONBOARD", 3, "abc-123");
                MdcSupport.Scope step = MdcSupport.withStep("api1", 2)) {
            seen = mdcOnWorker();
        }
        assertThat(seen).containsEntry("runId", runId.toString()).containsEntry("pipeline", "DEMO_ONBOARD")
                .containsEntry("version", "3").containsEntry("stepId", "api1").containsEntry("attempt", "2")
                .containsEntry("correlationId", "abc-123");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void workerContextDoesNotLeakIntoTheNextTask() throws Exception {
        try (MdcSupport.Scope run = MdcSupport.withRun(UUID.randomUUID(), "P", 1, "first")) {
            mdcOnWorker();
        }
        assertThat(mdcOnWorker()).isNullOrEmpty();
    }

    @Test
    void scopesRestoreOuterValues() {
        MDC.put("stepId", "outer");
        try (MdcSupport.Scope step = MdcSupport.withStep("inner", 1)) {
            assertThat(MDC.get("stepId")).isEqualTo("inner");
        }
        assertThat(MDC.get("stepId")).isEqualTo("outer");
        assertThat(MDC.get("attempt")).isNull();
        try (MdcSupport.Scope run = MdcSupport.withRun(UUID.randomUUID(), "P", 1, null)) {
            assertThat(MDC.get("correlationId")).isNull();
        }
    }
}
