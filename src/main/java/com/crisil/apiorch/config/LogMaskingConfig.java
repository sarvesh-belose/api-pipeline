package com.crisil.apiorch.config;

import org.springframework.context.annotation.Configuration;

import com.crisil.apiorch.observability.MaskingMessageConverter;

/** Applies {@code apiorch.logging.always-mask-keys} to the log message masking at startup (US-7.3). */
@Configuration(proxyBeanMethods = false)
public class LogMaskingConfig {

    public LogMaskingConfig(ApiorchProperties properties) {
        MaskingMessageConverter.configure(properties.logging().alwaysMaskKeys());
    }
}
