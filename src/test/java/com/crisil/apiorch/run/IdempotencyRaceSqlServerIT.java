package com.crisil.apiorch.run;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import testsupport.db.SharedSqlServer;

/** Slow tier: the idempotency race on SQL Server, against the filtered unique index (ADR-003 F16, rule 6). */
@Tag("slow")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class IdempotencyRaceSqlServerIT extends IdempotencyRaceContract {
}
