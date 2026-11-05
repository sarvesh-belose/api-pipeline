package com.crisil.apiorch.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts working data at rest (US-7.6 core): {@code run_context.content} and, from US-6.5, the outbox payload.
 * AES-256-GCM under a per-run key derived with HKDF-SHA256 from the master key of {@code kid} (salt = the run ID's
 * 16 bytes, info = {@code apiorch-context-v1}), a random 96-bit IV, a 128-bit tag and AAD {@code runId|entryKey},
 * so a value can't be moved to another row. Stored as {@code v1:<kid>:<base64(iv|ciphertext|tag)>}. Old kids keep
 * decrypting while configured; new values use the active kid. Without keys (the {@code local} profile only, enforced
 * where the cipher is configured) values are stored as {@code plain:<text>}. Derived keys are never stored.
 */
public final class ContextCipher {

    static final String VERSION = "v1";
    static final String PLAIN = "plain:";
    private static final byte[] INFO = "apiorch-context-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** Decryption failed: tampered, moved to another row, or an unknown kid (AC4). */
    public static final class DecryptionException extends RuntimeException {

        public static final String CODE = "CONTEXT_DECRYPTION_FAILED";

        DecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Map<String, byte[]> masterKeys;
    private final String activeKid;
    private final SecureRandom random = new SecureRandom();

    private ContextCipher(Map<String, byte[]> masterKeys, String activeKid) {
        this.masterKeys = masterKeys;
        this.activeKid = activeKid;
    }

    /**
     * @param base64Keys kid to a base64 32-byte master key
     * @param activeKid  the kid new values are written with; must be one of {@code base64Keys}
     */
    public static ContextCipher of(Map<String, String> base64Keys, String activeKid) {
        if (base64Keys.isEmpty()) {
            throw new IllegalArgumentException("No context encryption keys configured");
        }
        java.util.LinkedHashMap<String, byte[]> keys = new java.util.LinkedHashMap<>();
        base64Keys.forEach((kid, value) -> {
            if (!kid.matches("[A-Za-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("Key id '" + kid + "' must match [A-Za-z0-9_-]{1,32}");
            }
            byte[] key = Base64.getDecoder().decode(value.strip());
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException("Key '" + kid + "' must be 32 bytes, base64-encoded");
            }
            keys.put(kid, key);
        });
        if (!keys.containsKey(activeKid)) {
            throw new IllegalArgumentException("Active key id '" + activeKid + "' is not configured");
        }
        return new ContextCipher(Map.copyOf(keys), activeKid);
    }

    /** No encryption: values are stored as {@code plain:<text>} ({@code local} only, AC5). */
    public static ContextCipher plain() {
        return new ContextCipher(Map.of(), null);
    }

    public boolean enabled() {
        return activeKid != null;
    }

    public String activeKid() {
        return activeKid;
    }

    public String encrypt(UUID runId, String entryKey, String plaintext) {
        if (!enabled()) {
            return PLAIN + plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derive(masterKeys.get(activeKid), runId), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(runId, entryKey));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return VERSION + ":" + activeKid + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Context encryption failed", e);
        }
    }

    public String decrypt(UUID runId, String entryKey, String stored) {
        if (stored.startsWith(PLAIN)) {
            return stored.substring(PLAIN.length());
        }
        String[] parts = stored.split(":", 3);
        if (parts.length != 3 || !parts[0].equals(VERSION)) {
            throw new DecryptionException("Unrecognised stored value format", null);
        }
        byte[] master = masterKeys.get(parts[1]);
        if (master == null) {
            throw new DecryptionException("Key id '" + parts[1] + "' is not configured", null);
        }
        try {
            byte[] in = Base64.getDecoder().decode(parts[2]);
            if (in.length < IV_BYTES + TAG_BITS / 8) {
                throw new DecryptionException("Stored value is too short", null);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(master, runId), "AES"),
                    new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(aad(runId, entryKey));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DecryptionException("Context value failed authentication", e);
        }
    }

    /** The kid a stored value was written with, or {@code null} for plain values (the startup report, AC3). */
    public static String kidOf(String stored) {
        if (stored == null || stored.startsWith(PLAIN)) {
            return null;
        }
        String[] parts = stored.split(":", 3);
        return parts.length == 3 ? parts[1] : null;
    }

    /** HKDF-SHA256 (RFC 5869) extract and one expand block: 32 bytes, enough for AES-256. */
    static byte[] derive(byte[] masterKey, UUID runId) throws GeneralSecurityException {
        byte[] salt = ByteBuffer.allocate(16).putLong(runId.getMostSignificantBits())
                .putLong(runId.getLeastSignificantBits()).array();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(masterKey);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(INFO);
        mac.update((byte) 1);
        return mac.doFinal();
    }

    private static byte[] aad(UUID runId, String entryKey) {
        return (runId + "|" + entryKey).getBytes(StandardCharsets.UTF_8);
    }
}
