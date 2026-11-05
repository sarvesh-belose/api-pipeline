package com.crisil.apiorch.pipeline;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import testsupport.db.SharedSqlServer;

/** Slow tier: the publish race on SQL Server, where {@code UPDLOCK} is real (ADR-003 F3, rule 6). */
@Tag("slow")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class ConcurrentPublishSqlServerIT extends ConcurrentPublishContract {
}
