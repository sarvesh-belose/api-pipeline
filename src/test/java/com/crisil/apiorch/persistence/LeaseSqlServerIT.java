package com.crisil.apiorch.persistence;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import testsupport.db.SharedSqlServer;

/** Slow tier: lease SQL on SQL Server, where the row lock and lock timeout are real. */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class LeaseSqlServerIT extends LeaseSqlContract {
}
