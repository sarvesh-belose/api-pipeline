package com.crisil.apiorch.endpoint;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import com.crisil.apiorch.ApiorchApplication;
import com.crisil.apiorch.http.EndpointClientRegistry;

import testsupport.web.EndpointJson;
import testsupport.web.Http;

/**
 * US-2.1 AC4: two nodes on one database. A base URL changed through node A reaches node B's next call within the
 * refresh interval, with no event between the nodes.
 */
class EndpointHotReloadIT {

    @RegisterExtension
    static WireMockExtension oldTarget = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension newTarget = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static ConfigurableApplicationContext nodeA;
    static ConfigurableApplicationContext nodeB;

    static ConfigurableApplicationContext node(String instance) {
        return new SpringApplicationBuilder(ApiorchApplication.class)
                .profiles("test")
                .run("--server.port=0", "--management.server.port=0", "--apiorch.endpoints.refresh-interval-ms=300",
                        "--spring.datasource.url=jdbc:h2:mem:hot-reload;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                        "--spring.application.name=apiorch-" + instance);
    }

    static int port(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }

    @BeforeAll
    static void start() {
        nodeA = node("a");
        nodeB = node("b");
    }

    @AfterAll
    static void stop() {
        nodeB.close();
        nodeA.close();
    }

    @Test
    void changeOnNodeAReachesNodeBWithinTheRefreshInterval() throws Exception {
        oldTarget.stubFor(get("/svc/health").willReturn(aResponse().withStatus(200)));
        newTarget.stubFor(get("/svc/health").willReturn(aResponse().withStatus(204)));
        Http a = new Http(port(nodeA));
        Http b = new Http(port(nodeB));

        a.postJson("/api/v1/admin/endpoints", EndpointJson.withHealth("hot-svc",
                "http://localhost:" + oldTarget.getPort() + "/svc", "/health", 2000));
        HttpResponse<String> before = b.postJson("/api/v1/admin/endpoints/hot-svc/probe", null);
        assertThat(before.body()).contains("\"httpStatus\":200");

        HttpResponse<String> update = a.putJson("/api/v1/admin/endpoints/hot-svc", EndpointJson.withHealth("hot-svc",
                "http://localhost:" + newTarget.getPort() + "/svc", "/health", 2000), "\"0\"");
        assertThat(update.statusCode()).isEqualTo(200);

        EndpointClientRegistry registryB = nodeB.getBean(EndpointClientRegistry.class);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                registryB.get("hot-svc").orElseThrow().config().baseUrl()).contains(":" + newTarget.getPort()));
        assertThat(b.postJson("/api/v1/admin/endpoints/hot-svc/probe", null).body()).contains("\"httpStatus\":204");

        assertThat(a.delete("/api/v1/admin/endpoints/hot-svc").statusCode()).isEqualTo(204);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(registryB.get("hot-svc")).isEmpty());
    }
}
