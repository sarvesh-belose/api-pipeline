package com.crisil.apiorch.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * US-1.1 AC6, walking-skeleton slice: every migration applies to an empty database, nothing drifts, and database
 * time is UTC. Runs unchanged on H2 (MigrationIT) and SQL Server (MigrationSqlServerIT). Sprint 1 adds the JPA
 * entity round trip under a non-UTC JVM zone and the partition assertion.
 */
abstract class MigrationContract {

    @Autowired Flyway flyway;
    @Autowired JdbcClient jdbc;

    @Test
    void everyMigrationAppliesAndNothingDrifts() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).isNotEmpty();
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState)).containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void baselineTablesExist() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shedlock").query(Integer.class).single()).isZero();
    }

    @Test
    void databaseTimeIsUtc() {
        LocalDateTime db = jdbc.sql("SELECT SYSUTCDATETIME()").query(LocalDateTime.class).single();
        assertThat(Duration.between(db, LocalDateTime.now(ZoneOffset.UTC)).abs()).isLessThan(Duration.ofSeconds(10));
    }
}
