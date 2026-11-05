package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** US-7.2 groundwork on both databases: one complete row per attempt, with true sizes and truncation flags. */
abstract class AttemptWriterContract {

    @Autowired AttemptWriter writer;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    AttemptRecord attempt(UUID run, String responseBody) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new AttemptRecord(run, "api1", 1, 1, "REQUEST", 3, "kyc-svc", "POST", "https://kyc.example.com/kyc",
                Map.of("Content-Type", "application/json"), "{\"customerId\":\"C-77\"}", 20, 201,
                Map.of("X-Ref", "r-1"), responseBody, responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "SUCCESS", null, null, "CLOSED", null, start, start.plusMillis(42), 42);
    }

    @Test
    void writesOneCompleteRow() {
        UUID run = UUID.randomUUID();
        UUID id = tx.execute(s -> writer.write(attempt(run, "{\"ref\":\"K-9\"}")));
        Map<String, Object> row = jdbc.sql("SELECT * FROM step_attempt WHERE id = :id").param("id", id).query().singleRow();
        assertThat(row).containsEntry("step_id", "api1").containsEntry("outcome", "SUCCESS")
                .containsEntry("response_status", 201).containsEntry("endpoint_name", "kyc-svc");
        assertThat(((Number) row.get("request_bytes")).longValue()).isEqualTo(20);
        assertThat(((Number) row.get("duration_ms")).longValue()).isEqualTo(42);
        assertThat((String) row.get("request_headers")).contains("application/json");
        assertThat(row.get("response_truncated")).isIn(false, 0, (byte) 0);
    }

    @Test
    void longBodiesAreTruncatedButSizesStayTrue() {
        UUID run = UUID.randomUUID();
        String big = "é".repeat(200_000);
        UUID id = tx.execute(s -> writer.write(attempt(run, big)));
        Map<String, Object> row = jdbc.sql("SELECT response_body, response_bytes, response_truncated FROM step_attempt "
                + "WHERE id = :id").param("id", id).query().singleRow();
        assertThat(((Number) row.get("response_bytes")).longValue()).isEqualTo(400_000);
        assertThat(row.get("response_truncated")).isIn(true, 1, (byte) 1);
        assertThat(((String) row.get("response_body")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(AttemptWriter.DEFAULT_MAX_BODY_BYTES);
    }

    @Test
    void writingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> writer.write(attempt(UUID.randomUUID(), "{}")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void truncationNeverSplitsACharacter() {
        assertThat(AttemptWriter.truncate("aé", 2)).satisfies(t -> {
            assertThat(t.text()).isEqualTo("a");
            assertThat(t.truncated()).isTrue();
        });
        assertThat(AttemptWriter.truncate(null, 2).truncated()).isFalse();
        assertThat(AttemptWriter.truncate("ab", 2).truncated()).isFalse();
    }
}
