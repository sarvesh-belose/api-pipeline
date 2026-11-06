package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.crisil.apiorch.persistence.RunRows;

import testsupport.db.SharedSqlServer;

/**
 * US-7.4: the timeline's reads of the partitioned tables touch only the partitions the run can be in. The actual
 * execution plan ({@code SET STATISTICS XML ON}) of each bounded query is read on SQL Server, the only database
 * here with partitions (ADR-003 F8). As a control, the attempt query without its time bound reads all of them.
 */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class TimelinePartitionEliminationIT {

    private static final Pattern ACCESSED = Pattern.compile("<PartitionsAccessed PartitionCount=\"(\\d+)\"");

    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired RunRows rows;
    @Autowired TimelineService timelines;

    /** The {@code PartitionCount} of every partitioned access in the query's actual plan. */
    private List<Integer> partitionsAccessed(String namedSql, UUID runId, Instant from, Instant to) throws Exception {
        org.springframework.jdbc.core.namedparam.ParsedSql parsed = NamedParameterUtils.parseSqlStatement(namedSql);
        org.springframework.jdbc.core.namedparam.MapSqlParameterSource params =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id", runId);
        if (from != null) {
            // bound exactly as TimelineService binds them
            params.addValue("from", TimelineService.bound(from)).addValue("to", TimelineService.bound(to));
        }
        String sql = NamedParameterUtils.substituteNamedParameters(parsed, params);
        Object[] values = NamedParameterUtils.buildValueArray(parsed, params, null);
        List<Integer> counts = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("SET STATISTICS XML ON");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) {
                    ps.setObject(i + 1, values[i]);
                }
                boolean isResult = ps.execute();
                while (true) {
                    if (isResult) {
                        try (ResultSet rs = ps.getResultSet()) {
                            boolean plan = rs.getMetaData().getColumnName(1).contains("Showplan");
                            while (rs.next()) {
                                if (plan) {
                                    Matcher m = ACCESSED.matcher(rs.getString(1));
                                    while (m.find()) {
                                        counts.add(Integer.parseInt(m.group(1)));
                                    }
                                }
                            }
                        }
                    } else if (ps.getUpdateCount() == -1) {
                        break;
                    }
                    isResult = ps.getMoreResults();
                }
            } finally {
                c.createStatement().execute("SET STATISTICS XML OFF");
            }
        }
        return counts;
    }

    @Test
    void boundedTimelineReadsTouchOnlyTheRunsPartitions() throws Exception {
        int total = jdbc.sql("SELECT fanout FROM sys.partition_functions WHERE name = 'pf_month'")
                .query(Integer.class).single();
        assertThat(total).isGreaterThanOrEqualTo(5);

        UUID runId = UUID.randomUUID();
        rows.insertRun(runId, "PARTITION_P", 1, "SYNC", "c-part", "ops-user", null, 0);
        jdbc.sql("""
                INSERT INTO step_attempt (id, run_id, step_id, exec_no, attempt_no, kind, event_seq, endpoint_name,
                    method, url, outcome, started_at, duration_ms)
                VALUES (:id, :run, 'api1', 1, 1, 'REQUEST', 1, 'svc', 'GET', 'http://x/y', 'SUCCESS', SYSUTCDATETIME(), 5)""")
                .param("id", UUID.randomUUID()).param("run", runId).update();
        // realistic volume over three months, so the optimizer plans as it would in production: on a near-empty
        // table it scans the clustered index, and a scan cannot eliminate partitions by parameter
        String numbers = "WITH n AS (SELECT TOP (3000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS i "
                + "FROM sys.all_objects a CROSS JOIN sys.all_objects b) ";
        jdbc.sql(numbers + """
                INSERT INTO step_attempt (id, run_id, step_id, exec_no, attempt_no, kind, event_seq, endpoint_name,
                    method, url, outcome, started_at, duration_ms)
                SELECT NEWID(), NEWID(), 'api1', 1, 1, 'REQUEST', 1, 'svc', 'GET', 'http://x/y', 'SUCCESS',
                    DATEADD(MONTH, i % 3, SYSUTCDATETIME()), 5 FROM n""").update();
        jdbc.sql(numbers + """
                INSERT INTO run_event (run_id, seq, event_type, occurred_at)
                SELECT NEWID(), 1, 'RUN_STARTED', DATEADD(MONTH, i % 3, SYSUTCDATETIME()) FROM n""").update();
        jdbc.sql(numbers + """
                INSERT INTO run_exchange (id, run_id, kind, event_seq, bytes, occurred_at)
                SELECT NEWID(), NEWID(), 'INBOUND_REQUEST', 1, 10, DATEADD(MONTH, i % 3, SYSUTCDATETIME()) FROM n""")
                .update();
        for (String table : List.of("step_attempt", "run_event", "run_exchange")) {
            jdbc.sql("UPDATE STATISTICS " + table + " WITH FULLSCAN").update();
        }
        Instant now = Instant.now();
        Instant from = now.minus(1, ChronoUnit.DAYS);
        Instant to = now.plus(1, ChronoUnit.DAYS);

        for (String sql : List.of(timelines.attemptsSql(), timelines.exchangesSql(), timelines.eventsSql())) {
            List<Integer> accessed = partitionsAccessed(sql, runId, from, to);
            assertThat(accessed).as(sql).isNotEmpty();
            // a day either side of now spans at most two months
            assertThat(accessed).as(sql).allMatch(n -> n <= 2);
        }
        List<Integer> unbounded = partitionsAccessed("SELECT event_seq FROM step_attempt WHERE run_id = :id",
                runId, null, null);
        assertThat(unbounded).as("control: without the bound every partition is read").contains(total);
    }
}
