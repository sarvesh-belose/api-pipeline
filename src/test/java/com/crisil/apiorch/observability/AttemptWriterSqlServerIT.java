package com.crisil.apiorch.observability;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import testsupport.db.SharedSqlServer;

/** Slow tier: attempt rows on SQL Server, routed to their month partition on insert. */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class AttemptWriterSqlServerIT extends AttemptWriterContract {
}
