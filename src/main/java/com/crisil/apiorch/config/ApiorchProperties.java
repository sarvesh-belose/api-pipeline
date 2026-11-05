package com.crisil.apiorch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The single typed home for the {@code apiorch.*} keys (README §11). Startup fails on invalid values. Each story
 * adds its own section; defaults match the README catalogue.
 */
@Validated
@ConfigurationProperties("apiorch")
public record ApiorchProperties(
        @Valid @DefaultValue Correlation correlation,
        @Valid @DefaultValue Definition definition,
        @Valid @DefaultValue Run run,
        @Valid @DefaultValue Callback callback) {

    /** US-1.3. */
    public record Correlation(@DefaultValue("X-Correlation-Id") @NotBlank @Pattern(regexp = "[A-Za-z0-9-]+") String header) {
    }

    /** US-3.1. */
    public record Definition(@DefaultValue("524288") @Min(1024) long maxDocumentBytes) {
    }

    /** US-4.1. */
    public record Run(@DefaultValue("1048576") @Min(1024) long maxInputBytes) {
    }

    /** US-6.2. */
    public record Callback(@DefaultValue("1048576") @Min(1024) long maxBodyBytes) {
    }
}
