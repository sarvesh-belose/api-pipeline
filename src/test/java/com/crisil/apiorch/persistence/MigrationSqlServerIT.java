package com.crisil.apiorch.persistence;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import testsupport.db.SharedSqlServer;

/** Slow tier: the same migrations on SQL Server 2022 (`common/` + `sqlserver/`). */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class MigrationSqlServerIT extends MigrationContract {
}
