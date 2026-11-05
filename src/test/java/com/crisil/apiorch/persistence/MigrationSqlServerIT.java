package com.crisil.apiorch.persistence;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Slow tier: the same migrations on SQL Server 2022 (`common/` + `sqlserver/`). */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MigrationSqlServerIT extends MigrationContract {

    @Container
    @ServiceConnection
    static MSSQLServerContainer<?> mssql = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();
}
