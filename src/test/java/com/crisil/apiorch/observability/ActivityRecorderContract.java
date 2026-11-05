package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.crisil.apiorch.observability.ActivityRecorder.StepRef;
import com.crisil.apiorch.persistence.SqlDialect;

/** US-7.1 AC2–AC4 and AC6 on both databases (ActivityRecorderIT on H2, ActivityRecorderSqlServerIT, slow). */
abstract class ActivityRecorderContract {

    @Autowired ActivityRecorder recorder;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired SqlDialect dialect;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger activity = (Logger) LoggerFactory.getLogger("com.crisil.apiorch.activity");

    @BeforeEach
    void captureLogs() {
        logs.start();
        activity.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        activity.detachAppender(logs);
    }

    UUID newRun() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pipeline_run (id, pipeline_code, pipeline_version, status, mode, correlation_id, created_by)
                VALUES (:id, 'DEMO_ONBOARD', 1, 'ACCEPTED', 'SYNC', 'c-1', 'caller')""").param("id", id).update();
        return id;
    }

    private List<Long> seqs(UUID run) {
        return jdbc.sql("SELECT seq FROM run_event WHERE run_id = :r ORDER BY occurred_at, seq").param("r", run)
                .query(Long.class).list();
    }

    @Test
    void eventsGetConsecutiveSeqsAndALogLineAfterCommit() {
        UUID run = newRun();
        long first = tx.execute(s -> recorder.record(run, EventType.RUN_ACCEPTED, null,
                Map.of("mode", "SYNC", "version", 1, "inputBytes", 17), null));
        long second = tx.execute(s -> recorder.record(run, EventType.STEP_FAILED, StepRef.attempt("api1", 1, 2),
                Map.of("code", "HTTP_ERROR", "httpStatus", 503), "ops-user"));
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        Map<String, Object> row = jdbc.sql("SELECT * FROM run_event WHERE run_id = :r AND seq = 2").param("r", run)
                .query().singleRow();
        assertThat(row).containsEntry("event_type", "STEP_FAILED").containsEntry("step_id", "api1")
                .containsEntry("exec_no", 1).containsEntry("attempt", 2).containsEntry("actor", "ops-user");
        assertThat((String) row.get("details")).contains("\"code\":\"HTTP_ERROR\"");
        assertThat(row.get("instance_id")).isNotNull();

        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("event=RUN_ACCEPTED");
        assertThat(logs.list.get(0).getLevel().toString()).isEqualTo("INFO");
        assertThat(logs.list.get(1).getLevel().toString()).isEqualTo("WARN");
        assertThat(logs.list.get(1).getFormattedMessage()).doesNotContain("HTTP_ERROR");
    }

    @Test
    void rolledBackEventLeavesNoRowAndNoLogLine() {
        UUID run = newRun();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            recorder.record(run, EventType.RUN_STARTED, null, Map.of(), null);
            throw new IllegalStateException("roll back");
        })).hasMessage("roll back");
        assertThat(seqs(run)).isEmpty();
        assertThat(jdbc.sql("SELECT event_seq FROM pipeline_run WHERE id = :id").param("id", run)
                .query(Long.class).single()).isZero();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void recordingOutsideATransactionIsRefused() {
        UUID run = newRun();
        assertThatThrownBy(() -> recorder.record(run, EventType.RUN_STARTED, null, Map.of(), null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void unknownRunIsAnError() {
        assertThatThrownBy(() -> tx.execute(s -> recorder.record(UUID.randomUUID(), EventType.RUN_STARTED, null,
                Map.of(), null))).hasMessageContaining("does not exist");
    }

    @Test
    void fiftyConcurrentWritersGetGapFreeSeqs() throws Exception {
        UUID run = newRun();
        int writers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        String lock = dialect.lockingSelect("id", "pipeline_run", "id = :id");
        for (int i = 0; i < writers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return tx.execute(s -> {
                    jdbc.sql(lock).param("id", run).query(UUID.class).single();
                    return recorder.record(run, EventType.RESPONSE_RECEIVED, StepRef.of("api1", 1), Map.of(), null);
                });
            }));
        }
        start.countDown();
        List<Long> returned = new ArrayList<>();
        for (Future<Long> f : results) {
            returned.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        List<Long> expected = new ArrayList<>();
        for (long i = 1; i <= writers; i++) {
            expected.add(i);
        }
        assertThat(returned).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seqs(run)).containsExactlyElementsOf(expected);
        List<LocalDateTime> times = jdbc.sql("SELECT occurred_at FROM run_event WHERE run_id = :r ORDER BY seq")
                .param("r", run).query(LocalDateTime.class).list();
        assertThat(times).isSorted();
    }

    @Test
    void oversizedDetailsAreTruncated() {
        UUID run = newRun();
        tx.execute(s -> recorder.record(run, EventType.RUN_FAILED, null, Map.of("message", "x".repeat(10_000)), null));
        String details = jdbc.sql("SELECT details FROM run_event WHERE run_id = :r").param("r", run)
                .query(String.class).single();
        assertThat(details).contains("\"truncated\":true", "message").hasSizeLessThan(4096);
    }
}
