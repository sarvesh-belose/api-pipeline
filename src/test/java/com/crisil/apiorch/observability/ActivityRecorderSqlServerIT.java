package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import testsupport.db.SharedSqlServer;

/** Slow tier: the activity log on SQL Server, where the row lock is real, plus partition routing (US-7.1). */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class ActivityRecorderSqlServerIT extends ActivityRecorderContract {

    @Test
    void eventsLandInTheirMonthPartition() {
        UUID run = newRun();
        tx.execute(s -> recorder.record(run, EventType.RUN_ACCEPTED, null, Map.of("mode", "SYNC"), null));
        Map<String, Object> row = jdbc.sql("""
                SELECT $PARTITION.pf_month(occurred_at) AS p, $PARTITION.pf_month(SYSUTCDATETIME()) AS current_p
                FROM run_event WHERE run_id = :r""").param("r", run).query().singleRow();
        assertThat(row.get("p")).isEqualTo(row.get("current_p"));
    }
}
