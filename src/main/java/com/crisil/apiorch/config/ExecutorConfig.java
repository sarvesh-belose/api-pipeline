package com.crisil.apiorch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.crisil.apiorch.observability.MdcTaskDecorator;

/**
 * The engine's thread pools (US-4.1, US-4.3). {@code step-io} has a fixed size: a {@code ThreadPoolExecutor} only
 * grows past its core size when the queue is full, so core equals max. No thread ever waits for a run.
 */
@Configuration(proxyBeanMethods = false)
public class ExecutorConfig {

    public static final String STEP_IO = "stepIoExecutor";
    public static final String RUN_RESPONSE = "runResponseExecutor";

    @Bean(STEP_IO)
    ThreadPoolTaskExecutor stepIoExecutor(ApiorchProperties properties) {
        ApiorchProperties.Executor.StepIo stepIo = properties.executor().stepIo();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("step-io-");
        executor.setCorePoolSize(stepIo.size());
        executor.setMaxPoolSize(stepIo.size());
        executor.setQueueCapacity(stepIo.queueCapacity());
        executor.setAllowCoreThreadTimeOut(false);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    /** Builds sync responses off the timer thread that times waiters out (US-4.1). */
    @Bean(RUN_RESPONSE)
    ThreadPoolTaskExecutor runResponseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("run-response-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(10_000);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
