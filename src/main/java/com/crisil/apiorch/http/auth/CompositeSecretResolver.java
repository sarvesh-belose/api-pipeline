package com.crisil.apiorch.http.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import com.crisil.apiorch.config.ApiorchProperties;

/**
 * {@code env:} and {@code file:} references, re-checked against {@link SecretReferencePolicy} on every resolve and
 * cached for {@code apiorch.secrets.cache-ttl-ms}, so a rotated file is picked up without a restart (US-2.2 AC6).
 * File values are read as UTF-8 and trimmed. Failures are not cached.
 */
@Component
public class CompositeSecretResolver implements SecretResolver {

    private final SecretReferencePolicy policy;
    private final Function<String, String> environment;
    private final Cache<String, String> cache;

    @Autowired
    public CompositeSecretResolver(SecretReferencePolicy policy, ApiorchProperties properties) {
        this(policy, System::getenv, properties.secrets().cacheTtlMs(), Ticker.systemTicker());
    }

    CompositeSecretResolver(SecretReferencePolicy policy, Function<String, String> environment, long ttlMs,
            Ticker ticker) {
        this.policy = policy;
        this.environment = environment;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(ttlMs)).ticker(ticker).build();
    }

    @Override
    public Optional<String> resolve(String ref) {
        if (!policy.violations(ref).isEmpty()) {
            return Optional.empty();
        }
        String cached = cache.getIfPresent(ref);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> value = ref.startsWith("env:") ? env(ref.substring(4)) : file(ref.substring(5));
        value.ifPresent(v -> cache.put(ref, v));
        return value;
    }

    private Optional<String> env(String name) {
        String value = environment.apply(name);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<String> file(String path) {
        try {
            String value = Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
