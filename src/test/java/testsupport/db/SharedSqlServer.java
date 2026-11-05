package testsupport.db;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * One SQL Server 2022 container for the slow tier. Tests use {@code @ImportTestcontainers(SharedSqlServer.class)};
 * classes with the same configuration share one Spring context and therefore one container, which saves the
 * 30–60 s start per class (ADR-003 F13).
 */
public interface SharedSqlServer {

    @Container
    @ServiceConnection
    MSSQLServerContainer<?> MSSQL = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();
}
