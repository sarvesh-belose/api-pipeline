package com.crisil.apiorch.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.crisil.apiorch.observability.MdcTaskDecorator;

/** Scheduled jobs (endpoint refresh now; sweepers later). Scheduler threads get MDC propagation (US-1.3). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

    @Bean
    ThreadPoolTaskSchedulerCustomizer mdcSchedulerCustomizer() {
        return scheduler -> scheduler.setTaskDecorator(new MdcTaskDecorator());
    }
}
