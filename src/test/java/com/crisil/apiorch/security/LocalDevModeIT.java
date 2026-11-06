package com.crisil.apiorch.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.crisil.apiorch.ApiorchApplication;

import testsupport.web.EndpointJson;
import testsupport.web.Http;

/**
 * US-1.2 AC7: the {@code local} profile starts in dev mode, and every request is {@code dev-user} with all roles,
 * including the async dispatch that answers a sync run.
 */
class LocalDevModeIT {

    @Test
    void localProfileRunsAsDevUser() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ApiorchApplication.class)
                .profiles("local")
                .run("--server.port=0", "--management.server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:local-dev;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")) {
            Http http = Http.anonymous(((WebServerApplicationContext) ctx).getWebServer().getPort());
            HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints",
                    EndpointJson.minimal("dev-svc", "http://localhost:9999"));
            assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
            assertThat(res.body()).contains("\"createdBy\":\"dev-user\"");
            assertThat(http.get("/v3/api-docs").statusCode()).isEqualTo(200);

            // a sync run answers on an async dispatch, which must still be dev-user (found running the demo); the
            // seeded pipeline's WireMock is not running here, so the run fails, but the caller gets its 200
            HttpResponse<String> run = http.postJson("/api/v1/pipelines/DEMO_ONBOARD/runs?mode=sync", "{\"name\":\"x\"}");
            assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
            assertThat(run.body()).contains("\"status\":\"FAILED\"");
        }
    }
}
