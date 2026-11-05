package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** US-7.6 core (AC3–AC6): round trip, row binding, tamper, rotation, plain mode, per-run keys. */
class ContextCipherTest {

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) seed);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private final ContextCipher k1 = ContextCipher.of(Map.of("k1", key(1)), "k1");
    private final UUID run = UUID.randomUUID();

    @Test
    void roundTripIsOpaqueAndPrefixed() {
        String stored = k1.encrypt(run, "input", "{\"pan\":\"ABCDE1234F\"}");
        assertThat(stored).startsWith("v1:k1:").doesNotContain("ABCDE");
        assertThat(k1.decrypt(run, "input", stored)).isEqualTo("{\"pan\":\"ABCDE1234F\"}");
        assertThat(k1.encrypt(run, "input", "same")).isNotEqualTo(k1.encrypt(run, "input", "same"));
        assertThat(ContextCipher.kidOf(stored)).isEqualTo("k1");
        assertThat(k1.enabled()).isTrue();
    }

    @Test
    void valueMovedToAnotherRowOrRunFails() {
        String stored = k1.encrypt(run, "api1", "{}");
        assertThatThrownBy(() -> k1.decrypt(run, "api2", stored)).isInstanceOf(ContextCipher.DecryptionException.class);
        assertThatThrownBy(() -> k1.decrypt(UUID.randomUUID(), "api1", stored))
                .isInstanceOf(ContextCipher.DecryptionException.class);
    }

    @Test
    void tamperingFails() {
        String stored = k1.encrypt(run, "input", "{\"a\":1}");
        byte[] raw = Base64.getDecoder().decode(stored.substring("v1:k1:".length()));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:k1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> k1.decrypt(run, "input", tampered)).isInstanceOf(ContextCipher.DecryptionException.class)
                .hasMessageContaining("authentication");
        assertThatThrownBy(() -> k1.decrypt(run, "input", "v1:k1:AAAA")).isInstanceOf(ContextCipher.DecryptionException.class);
        assertThatThrownBy(() -> k1.decrypt(run, "input", "v1:k1:%%%")).isInstanceOf(ContextCipher.DecryptionException.class);
        assertThatThrownBy(() -> k1.decrypt(run, "input", "v9:k1:x")).isInstanceOf(ContextCipher.DecryptionException.class);
        assertThatThrownBy(() -> k1.decrypt(run, "input", "garbage")).isInstanceOf(ContextCipher.DecryptionException.class);
    }

    @Test
    void rotationWritesTheActiveKidAndReadsOldOnes() {
        String old = k1.encrypt(run, "input", "old");
        ContextCipher rotated = ContextCipher.of(Map.of("k1", key(1), "k2", key(2)), "k2");
        String fresh = rotated.encrypt(run, "input", "new");
        assertThat(fresh).startsWith("v1:k2:");
        assertThat(rotated.decrypt(run, "input", old)).isEqualTo("old");
        assertThat(rotated.decrypt(run, "input", fresh)).isEqualTo("new");
        assertThatThrownBy(() -> k1.decrypt(run, "input", fresh)).isInstanceOf(ContextCipher.DecryptionException.class)
                .hasMessageContaining("k2");
        assertThat(rotated.activeKid()).isEqualTo("k2");
    }

    @Test
    void plainModeAndInvalidConfiguration() {
        ContextCipher plain = ContextCipher.plain();
        assertThat(plain.enabled()).isFalse();
        String stored = plain.encrypt(run, "input", "{}");
        assertThat(stored).isEqualTo("plain:{}");
        assertThat(plain.decrypt(run, "input", stored)).isEqualTo("{}");
        assertThat(k1.decrypt(run, "input", stored)).isEqualTo("{}");
        assertThat(ContextCipher.kidOf(stored)).isNull();
        assertThat(ContextCipher.kidOf(null)).isNull();
        assertThatThrownBy(() -> ContextCipher.of(Map.of(), "k1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextCipher.of(Map.of("k1", key(1)), "k2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextCipher.of(Map.of("k1", "c2hvcnQ="), "k1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextCipher.of(Map.of("bad kid", key(1)), "bad kid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRunGetsItsOwnDerivedKey() throws Exception {
        byte[] master = Base64.getDecoder().decode(key(7));
        Set<String> derived = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            derived.add(Base64.getEncoder().encodeToString(ContextCipher.derive(master, UUID.randomUUID())));
        }
        assertThat(derived).hasSize(1000);
        UUID fixed = UUID.randomUUID();
        assertThat(ContextCipher.derive(master, fixed)).isEqualTo(ContextCipher.derive(master, fixed)).hasSize(32);
    }
}
