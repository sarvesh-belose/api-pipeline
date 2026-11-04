package probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Slow tier: the same contract on real SQL Server 2022, plus the SQL Server-only features. */
@Tag("slow")
@SpringBootTest
@Testcontainers
class SqlServerIT extends DatabaseContract {

    @Container
    @ServiceConnection
    static MSSQLServerContainer<?> mssql = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    @Test
    void jsonValidityIsEnforced() {
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO run_event(run_id, seq, occurred_at, event_type, details) "
                + "VALUES (:r, 1, SYSUTCDATETIME(), 'X', 'not json')").param("r", run).update())
                .satisfies(e -> report("json-check|invalid JSON rejected: " + rootMessage(e)));
    }

    @Test
    void currentMonthLandsInItsPartition() {
        Integer p = jdbc.sql("SELECT $PARTITION.pf_month(SYSUTCDATETIME())").query(Integer.class).single();
        Integer count = jdbc.sql("SELECT COUNT(*) FROM sys.partitions WHERE object_id = OBJECT_ID('run_event') AND index_id = 1")
                .query(Integer.class).single();
        report("partition|current month -> partition " + p + " of " + count + " (RANGE RIGHT; 1 = before current month)");
        assertThat(p).isEqualTo(2);
    }

    @Test
    void appendOnlyGrantRefusesUpdate() {
        UUID run = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            jdbc.sql("EXECUTE AS USER = 'probe_app'").update();
            try {
                jdbc.sql("INSERT INTO run_event(run_id, seq, occurred_at, event_type, details) "
                        + "VALUES (:r, 1, SYSUTCDATETIME(), 'RUN_ACCEPTED', '{}')").param("r", run).update();
            } finally {
                jdbc.sql("REVERT").update();
            }
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("EXECUTE AS USER = 'probe_app'").update();
            try {
                jdbc.sql("UPDATE run_event SET event_type = 'X' WHERE run_id = :r").param("r", run).update();
            } finally {
                jdbc.sql("REVERT").update();
            }
        })).satisfies(e -> report("grant|application role UPDATE refused as " + e.getClass().getSimpleName()
                + ": " + rootMessage(e)));
    }
}
