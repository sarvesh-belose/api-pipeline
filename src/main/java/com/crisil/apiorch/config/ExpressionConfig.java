package com.crisil.apiorch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.GuardedJsonataEvaluator;

/** The expression engine chosen by gate G2 (ADR-002), bounded by {@code apiorch.expression.*}. */
@Configuration(proxyBeanMethods = false)
public class ExpressionConfig {

    @Bean
    ExpressionEvaluator expressionEvaluator(ApiorchProperties properties, ObjectMapper json) {
        ApiorchProperties.Expression e = properties.expression();
        return new GuardedJsonataEvaluator(e.timeoutMs(), e.maxDepth(), e.maxLength(), e.maxNesting(), json);
    }
}
