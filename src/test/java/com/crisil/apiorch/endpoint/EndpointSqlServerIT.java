package com.crisil.apiorch.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;

import testsupport.db.SharedSqlServer;

/** Slow tier: endpoint rows (UUID, JSON columns with ISJSON checks, @Version) and the reference query on SQL Server. */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class EndpointSqlServerIT {

    @Autowired EndpointService service;
    @Autowired EndpointReferenceChecker references;

    @Test
    void createUpdateAndDeleteOnSqlServer() {
        EndpointRequest create = new EndpointRequest("mssql-svc", "d", "https://a.example.com/api", "/health", 1000,
                2000, null, null, null, new EndpointRequest.Resilience(null, new EndpointRequest.Bulkhead(5, 0), null));
        EndpointConfig created = service.create(create, "tester");
        assertThat(created.version()).isZero();
        assertThat(created.bulkhead().maxConcurrentCalls()).isEqualTo(5);

        EndpointRequest update = new EndpointRequest("mssql-svc", "d2", "https://b.example.com/api", "/health", 1000,
                3000, EndpointRequest.HttpVersion.HTTP_2, false, null, null);
        EndpointConfig updated = service.update("mssql-svc", update, 0L, "tester");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(service.get("mssql-svc").enabled()).isFalse();
        assertThat(service.get("mssql-svc").bulkhead().inherited()).isTrue();

        assertThat(references.referencesTo("mssql-svc")).isEmpty();
        service.delete("mssql-svc", "tester");
        assertThat(service.list()).extracting(EndpointConfig::name).doesNotContain("mssql-svc");
    }
}
