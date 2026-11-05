package com.crisil.apiorch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.crisil.apiorch.persistence.ContextCipher;

/** US-7.6 AC5 and key resolution: no keys outside {@code local} fails startup; references resolve once. */
class ContextCipherConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TempDir Path dir;

    private static ApiorchProperties.Context settings(Map<String, String> keys, String active) {
        return new ApiorchProperties.Context(keys, active);
    }

    @Test
    void noKeysFailsOutsideLocalAndIsPlainInLocal() {
        assertThatThrownBy(() -> ContextCipherConfig.build(settings(Map.of(), null), false, n -> null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("encryption-keys");
        ContextCipher plain = ContextCipherConfig.build(settings(Map.of(), null), true, n -> null);
        assertThat(plain.enabled()).isFalse();
        assertThat(plain.encrypt(UUID.randomUUID(), "input", "{}")).isEqualTo("plain:{}");
    }

    @Test
    void envAndFileReferencesResolve() throws Exception {
        Path file = dir.resolve("k2.key");
        Files.writeString(file, KEY + "\n");
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("k1", "env:APIORCH_SECRET_CTX_K1");
        keys.put("k2", "file:" + file);
        ContextCipher cipher = ContextCipherConfig.build(settings(keys, "k2"), false,
                n -> n.equals("APIORCH_SECRET_CTX_K1") ? KEY : null);
        assertThat(cipher.activeKid()).isEqualTo("k2");
        assertThat(cipher.encrypt(UUID.randomUUID(), "input", "{}")).startsWith("v1:k2:");

        ContextCipher single = ContextCipherConfig.build(settings(Map.of("only", "env:X"), null), false, n -> KEY);
        assertThat(single.activeKid()).isEqualTo("only");
    }

    @Test
    void badConfigurationFailsStartup() {
        assertThatThrownBy(() -> ContextCipherConfig.build(settings(Map.of("k1", "env:MISSING"), "k1"), false, n -> null))
                .hasMessageContaining("does not resolve");
        assertThatThrownBy(() -> ContextCipherConfig.build(settings(Map.of("k1", "file:" + dir.resolve("absent")),
                "k1"), false, n -> null)).hasMessageContaining("does not resolve");
        assertThatThrownBy(() -> ContextCipherConfig.build(settings(Map.of("k1", KEY), "k1"), false, n -> null))
                .hasMessageContaining("env: or file:");
        Map<String, String> two = Map.of("k1", "env:A", "k2", "env:B");
        assertThatThrownBy(() -> ContextCipherConfig.build(settings(two, null), false, n -> KEY))
                .hasMessageContaining("active-kid");
    }
}
