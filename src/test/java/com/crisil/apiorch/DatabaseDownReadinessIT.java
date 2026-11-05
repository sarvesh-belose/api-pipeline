package com.crisil.apiorch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import testsupport.web.Http;

/**
 * US-1.1 AC3: with the database unreachable the app still starts, readiness reports DOWN and liveness stays UP.
 * Points at a SQL Server port nothing listens on; Flyway and JDBC metadata access are off so startup does not
 * need the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlserver://127.0.0.1:1;databaseName=apiorch;encrypt=false;loginTimeout=1",
        "spring.datasource.username=nobody",
        "spring.datasource.password=",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.flyway.enabled=false",
        "spring.jpa.database-platform=org.hibernate.dialect.SQLServerDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"})
@ActiveProfiles("test")
class DatabaseDownReadinessIT {

    @Value("${local.management.port}") int managementPort;

    @Test
    void readinessIsDownAndLivenessIsUp() throws Exception {
        Http management = new Http(managementPort);
        HttpResponse<String> readiness = management.get("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
        HttpResponse<String> liveness = management.get("/actuator/health/liveness");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
    }
}
