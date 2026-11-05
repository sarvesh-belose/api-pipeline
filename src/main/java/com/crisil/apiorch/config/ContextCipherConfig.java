package com.crisil.apiorch.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.crisil.apiorch.persistence.ContextCipher;

/**
 * The {@link ContextCipher} for {@code run_context} (US-7.6). Keys come from {@code apiorch.context.encryption-keys},
 * each an {@code env:NAME} or {@code file:/path} reference resolved once at startup. These are operator-configured
 * infrastructure secrets, so the endpoint reference policy (US-2.2), which guards admin-supplied references, does
 * not apply. Without keys, startup fails unless the {@code local} profile is active, where values are stored as
 * {@code plain:} with a WARN (AC5).
 */
@Configuration(proxyBeanMethods = false)
public class ContextCipherConfig {

    private static final Logger log = LoggerFactory.getLogger(ContextCipherConfig.class);

    @Bean
    ContextCipher contextCipher(ApiorchProperties properties, Environment environment) {
        return build(properties.context(), Arrays.asList(environment.getActiveProfiles()).contains("local"),
                System::getenv);
    }

    static ContextCipher build(ApiorchProperties.Context settings, boolean local, Function<String, String> env) {
        if (settings.encryptionKeys().isEmpty()) {
            if (!local) {
                throw new IllegalStateException("apiorch.context.encryption-keys is empty: run_context must be "
                        + "encrypted outside the local profile (US-7.6)");
            }
            log.warn("run_context encryption is DISABLED (local profile, no apiorch.context.encryption-keys)");
            return ContextCipher.plain();
        }
        Map<String, String> keys = new LinkedHashMap<>();
        settings.encryptionKeys().forEach((kid, ref) -> keys.put(kid, resolve(kid, ref, env)));
        String active = settings.activeKid() != null ? settings.activeKid()
                : settings.encryptionKeys().size() == 1 ? keys.keySet().iterator().next() : null;
        if (active == null) {
            throw new IllegalStateException("apiorch.context.active-kid must name one of " + keys.keySet());
        }
        ContextCipher cipher = ContextCipher.of(keys, active);
        log.info("run_context encryption enabled activeKid={} kids={}", active, keys.keySet());
        return cipher;
    }

    static String resolve(String kid, String ref, Function<String, String> env) {
        String value = null;
        if (ref.startsWith("env:")) {
            value = env.apply(ref.substring(4));
        } else if (ref.startsWith("file:")) {
            try {
                value = Files.readString(Path.of(ref.substring(5)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                value = null;
            }
        } else {
            throw new IllegalStateException("apiorch.context.encryption-keys." + kid
                    + " must be an env: or file: reference");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("apiorch.context.encryption-keys." + kid + " does not resolve");
        }
        return value.strip();
    }
}
