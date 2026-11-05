package com.crisil.apiorch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import testsupport.web.Http;
import testsupport.web.TestEndpoints;

/**
 * US-1.1 AC8: on shutdown the server stops taking requests but lets in-flight ones finish. Starts its own context,
 * because closing the shared cached test context would break other tests.
 */
class GracefulShutdownIT {

    @Test
    void inFlightRequestFinishesDuringShutdown() throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ApiorchApplication.class, TestEndpoints.class)
                .profiles("test")
                .properties("server.port=0", "management.server.port=0",
                        "spring.datasource.url=jdbc:h2:mem:shutdown;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
                .run();
        int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
        Http http = new Http(port);

        CompletableFuture<HttpResponse<String>> inFlight = CompletableFuture.supplyAsync(() -> {
            try {
                return http.get("/api/v1/test/slow");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(400);
        long start = System.nanoTime();
        ctx.close();

        HttpResponse<String> res = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"done\":true");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
        assertThat(ctx.isActive()).isFalse();
    }
}
