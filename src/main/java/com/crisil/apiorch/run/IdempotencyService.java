package com.crisil.apiorch.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.ProblemTypes;
import com.crisil.apiorch.pipeline.Canonical;

/**
 * Inbound {@code Idempotency-Key} (US-4.7). A key is unique per pipeline and caller, {@code (pipeline_code,
 * created_by, idempotency_key)}, and stored with the SHA-256 of the canonical input (keys sorted, array order kept,
 * UTF-8). A repeat with the same input is a replay of the existing run, whatever its status; a different input is
 * {@code 422 idempotency-key-reuse}. The lookup is a plain read on the unique index, done before any other check.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    public static final String REUSE = "idempotency-key-reuse";

    /** The run a key already belongs to. */
    public record Existing(UUID runId, int version, String requestHash) {
    }

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** AC8: {@code null} means no key; a malformed key is {@code 400}. */
    public static String validate(String key) {
        if (key == null) {
            return null;
        }
        if (!KEY.matcher(key).matches()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "Idempotency-Key must be 1 to 128 characters of [A-Za-z0-9._:-].");
        }
        return key;
    }

    public static String hash(JsonNode input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Canonical.of(input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Existing> find(String code, String principal, String key) {
        return jdbc.sql("""
                SELECT id, pipeline_version, request_hash FROM pipeline_run
                WHERE pipeline_code = :code AND created_by = :by AND idempotency_key = :key""")
                .param("code", code).param("by", principal).param("key", key)
                .query((rs, i) -> new Existing(UUID.fromString(rs.getString("id")), rs.getInt("pipeline_version"),
                        rs.getString("request_hash")))
                .optional();
    }

    /** The existing run when {@code hash} matches; otherwise {@code 422} naming it (AC3). */
    public Existing replayOrReject(Existing existing, String hash, String key) {
        if (!existing.requestHash().equals(hash)) {
            log.warn("idempotency key reuse with different payload runId={} key={}", existing.runId(), key);
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, REUSE, "Idempotency-Key '" + key
                    + "' was already used for run " + existing.runId() + " with a different request body.");
        }
        log.info("idempotent replay runId={} key={}", existing.runId(), key);
        return existing;
    }
}
