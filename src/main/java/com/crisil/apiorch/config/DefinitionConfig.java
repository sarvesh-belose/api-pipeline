package com.crisil.apiorch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.crisil.apiorch.definition.DefinitionValidator;
import com.crisil.apiorch.definition.DslVersion;
import com.crisil.apiorch.expression.ExpressionEvaluator;

/** The pure definition rules (US-3.2), configured from this build and {@code apiorch.*}. */
@Configuration(proxyBeanMethods = false)
public class DefinitionConfig {

    /** Until US-7.3 makes it configurable, the default capture level is {@code METADATA} (README §11). */
    static final String DEFAULT_CAPTURE = "METADATA";

    @Bean
    DefinitionValidator definitionValidator(ApiorchProperties properties, ExpressionEvaluator evaluator) {
        return new DefinitionValidator(new DefinitionValidator.Settings(DslVersion.CURRENT,
                properties.run().maxSyncWaitMs(), properties.run().defaultRunTimeoutMs(), DEFAULT_CAPTURE), evaluator);
    }
}
