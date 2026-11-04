package probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine's SQL contract (README §5.1), run unchanged against H2 (MSSQL mode) and SQL Server.
 * Findings print as "G3|<db>|..." lines; they are the evidence for ADR-003.
 */
abstract class DatabaseContract {

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired RunRowRepo runs;
    @Autowired SqlDialect dialect;

    void report(String line) {
        System.out.println("G3|" + dialect.db() + "|" + line);
    }

    UUID insertRun(String owner, long epoch, long leaseMillis) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO pipeline_run(id, status, owner_instance, lease_epoch, lease_until, meta) "
                        + "VALUES (:id, 'ACCEPTED', :me, :e, " + dialect.nowPlus("ms") + ", :meta)")
                .param("id", id).param("me", owner).param("e", epoch).param("ms", leaseMillis)
                .param("meta", "{\"k\":1}").update();
        return id;
    }

    @Test
    void uuidUtcTimestampAndJsonTextRoundTrip() {
        UUID id = insertRun("node-a", 1, 30_000);
        RunRow row = runs.findById(id).orElseThrow();
        Instant now = Instant.now();
        report("roundtrip|createdAt=" + row.createdAt + "|jvmNow=" + now + "|leaseUntil=" + row.leaseUntil);
        assertThat(Duration.between(row.createdAt, now).abs()).as("created_at is UTC").isLessThan(Duration.ofSeconds(10));
        assertThat(Duration.between(now.plusSeconds(30), row.leaseUntil).abs()).isLessThan(Duration.ofSeconds(10));
        assertThat(row.meta).isEqualTo("{\"k\":1}");
    }

    @Test
    void leaseClaimPredicateOnDatabaseTime() {
        UUID id = insertRun("node-a", 1, -1_000); // already expired
        String claim = "UPDATE pipeline_run SET owner_instance = :me, lease_epoch = lease_epoch + 1, "
                + "lease_until = " + dialect.nowPlus("ms") + ", wakeup_requested = 0 "
                + "WHERE id = :id AND ((owner_instance IS NULL AND wakeup_requested = 1) "
                + "OR (owner_instance IS NOT NULL AND lease_until < " + dialect.now() + "))";
        int first = jdbc.sql(claim).param("me", "node-b").param("ms", 30_000).param("id", id).update();
        int second = jdbc.sql(claim).param("me", "node-c").param("ms", 30_000).param("id", id).update();
        report("claim|expired lease claimed=" + first + "|valid lease re-claimed=" + second);
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(runs.findById(id).orElseThrow().leaseEpoch).isEqualTo(2);
    }

    @Test
    void rowLockSyntaxMatrix() {
        UUID id = insertRun("node-a", 1, 30_000);
        String[][] forms = {
            {"FOR UPDATE", "SELECT id FROM pipeline_run WHERE id = :id AND owner_instance = :me AND lease_epoch = :e FOR UPDATE"},
            {"WITH (UPDLOCK, ROWLOCK)", "SELECT id FROM pipeline_run WITH (UPDLOCK, ROWLOCK) WHERE id = :id AND owner_instance = :me AND lease_epoch = :e"}};
        for (String[] f : forms) {
            String outcome;
            try {
                Integer n = tx.execute(s -> jdbc.sql(f[1]).param("id", id).param("me", "node-a").param("e", 1)
                        .query(UUID.class).list().size());
                outcome = "accepted rows=" + n;
            } catch (Exception e) {
                outcome = "REJECTED " + rootMessage(e);
            }
            report("lock-syntax|" + f[0] + "|" + outcome);
        }
        Integer viaDialect = tx.execute(s -> jdbc.sql(dialect.lockRunSql()).param("id", id).param("me", "node-a")
                .param("e", 1).query(UUID.class).list().size());
        assertThat(viaDialect).isEqualTo(1);
    }

    static final String LOCK_HINT = "SELECT id FROM pipeline_run WITH (UPDLOCK, ROWLOCK) "
            + "WHERE id = :id AND owner_instance = :me AND lease_epoch = :e";
    static final String LOCK_FOR_UPDATE = "SELECT id FROM pipeline_run "
            + "WHERE id = :id AND owner_instance = :me AND lease_epoch = :e FOR UPDATE";

    @Test
    void rowLockBlocksSecondLocker() throws Exception {
        report("lock-blocks|dialect form|" + lockBlocks(dialect.lockRunSql()));
        for (String form : new String[] {LOCK_HINT, LOCK_FOR_UPDATE}) {
            String label = form == LOCK_HINT ? "WITH (UPDLOCK, ROWLOCK)" : "FOR UPDATE";
            report("lock-blocks|" + label + "|" + lockBlocks(form));
        }
        assertThat(lockBlocks(dialect.lockRunSql())).startsWith("blocked");
    }

    /** H2 50200 and SQL Server 1222 are the lock-wait timeouts; any other error is not proof of blocking. */
    static boolean isLockTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se && (se.getErrorCode() == 50200 || se.getErrorCode() == 1222)) {
                return true;
            }
        }
        return false;
    }

    /** Holds the lock with {@code sql} in one transaction and tries the same lock from a second one. */
    String lockBlocks(String sql) throws Exception {
        UUID id = insertRun("node-a", 1, 30_000);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> f = holder.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.sql(sql).param("id", id).param("me", "node-a").param("e", 1).query(UUID.class).list();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        while (!locked.await(100, TimeUnit.MILLISECONDS)) {
            if (f.isDone()) {
                holder.shutdown();
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    return "REJECTED " + rootMessage(e);
                }
            }
        }
        long t0 = System.nanoTime();
        String outcome;
        try {
            tx.executeWithoutResult(s -> {
                dialect.sessionLockTimeoutSql(500).ifPresent(set -> jdbc.sql(set).update());
                jdbc.sql(dialect.withLockTimeout(sql, 500)).param("id", id).param("me", "node-a").param("e", 1).query(UUID.class).list();
            });
            outcome = "NOT BLOCKED (second locker got the row)";
        } catch (Exception e) {
            outcome = (isLockTimeout(e) ? "blocked then timed out: " : "FAILED (not a lock timeout): ") + rootMessage(e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();
        f.get(10, TimeUnit.SECONDS);
        holder.shutdown();
        return outcome + " after " + ms + "ms";
    }

    @Test
    void eventSeqAllocationIsGapFreeUnderConcurrency() throws Exception {
        UUID id = insertRun("node-a", 1, 30_000);
        int threads = 16;
        int per = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < per; i++) {
                    tx.executeWithoutResult(s -> {
                        jdbc.sql(dialect.lockRunSql()).param("id", id).param("me", "node-a").param("e", 1).query(UUID.class).list();
                        jdbc.sql("UPDATE pipeline_run SET event_seq = event_seq + 1 WHERE id = :id").param("id", id).update();
                        long seq = jdbc.sql("SELECT event_seq FROM pipeline_run WHERE id = :id").param("id", id)
                                .query(Long.class).single();
                        jdbc.sql("INSERT INTO run_event(run_id, seq, occurred_at, event_type, details) VALUES (:r, :s, "
                                + dialect.now() + ", 'STEP_SCHEDULED', '{}')").param("r", id).param("s", seq).update();
                    });
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long count = jdbc.sql("SELECT COUNT(*) FROM run_event WHERE run_id = :r").param("r", id).query(Long.class).single();
        long max = jdbc.sql("SELECT MAX(seq) FROM run_event WHERE run_id = :r").param("r", id).query(Long.class).single();
        report("event-seq|writers=" + threads + "|events=" + count + "|max seq=" + max);
        assertThat(count).isEqualTo((long) threads * per);
        assertThat(max).isEqualTo(count);
    }

    @Test
    void shedLockedSchedulerRuns() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ProbeApplication.Sweeper.RUNS.get() > 1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shedlock WHERE name = 'probe-sweeper'").query(Integer.class).single())
                .isEqualTo(1);
        report("shedlock|usingDbTime scheduler ran " + ProbeApplication.Sweeper.RUNS.get() + " times");
    }

    static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        String state = r instanceof java.sql.SQLException se ? " [SQLState=" + se.getSQLState() + ", code=" + se.getErrorCode() + "]" : "";
        String m = String.valueOf(r.getMessage()).replace('\n', ' ').replace('|', '/');
        return r.getClass().getSimpleName() + state + ": " + (m.length() > 160 ? m.substring(0, 160) + "…" : m);
    }
}
