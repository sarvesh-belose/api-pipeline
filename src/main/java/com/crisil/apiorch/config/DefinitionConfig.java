package com.crisil.apiorch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.crisil.apiorch.definition.DefinitionValidator;
import com.crisil.apiorch.definition.DslVersion;
import com.crisil.apiorch.expression.ExpressionEvaluator;

/** The pure definition rules (US-3.2), configured from this build and {@code apiorch.*}. */
@Configuration(proxyBeanMethods = false)
public class DefinitionConfig {

    @Bean
    DefinitionValidator definitionValidator(ApiorchProperties properties, ExpressionEvaluator evaluator) {
        return new DefinitionValidator(new DefinitionValidator.Settings(DslVersion.CURRENT,
                properties.run().maxSyncWaitMs(), properties.run().defaultRunTimeoutMs(),
                properties.logging().defaultCapture().name() + " (apiorch.logging.default-capture)"), evaluator);
    }
}
