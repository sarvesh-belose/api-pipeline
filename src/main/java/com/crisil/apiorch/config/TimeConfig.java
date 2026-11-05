package com.crisil.apiorch.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single application {@link Clock} (README §13). Components take it by constructor; tests replace it.
 * Database time ({@code SYSUTCDATETIME()}) stays the authority for leases, ShedLock and retention cutoffs.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
