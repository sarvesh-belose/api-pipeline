package com.crisil.apiorch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.node.ObjectNode;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.TestPipelines;

import testsupport.security.TestJwt;
import testsupport.web.Http;
import testsupport.web.TestEndpoints;

/**
 * US-1.4 AC2 and AC5: behind a trusted proxy, absolute links use the forwarded scheme and host; from an untrusted
 * address the forwarded headers are ignored. Actuator is not served on the API port.
 */
class ForwardedHeadersIT {

    static HttpResponse<String> postAccepted(int port) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/test/accepted"))
                .header("Authorization", "Bearer " + TestJwt.allRoles())
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "apiorch.test.example.internal")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1")
    @ActiveProfiles("test")
    @Import(TestEndpoints.class)
    class FromATrustedProxy {

        @Value("${local.server.port}") int port;

        @Autowired JdbcClient jdbc;

        @Test
        void locationUsesThePublicAddress() throws Exception {
            HttpResponse<String> res = postAccepted(port);
            assertThat(res.statusCode()).isEqualTo(202);
            assertThat(res.headers().firstValue("Location"))
                    .hasValue("https://apiorch.test.example.internal/api/v1/runs/r-1");
        }

        /** The real run endpoint too: its 202 points at the public address (found by the load-balancer check). */
        @Test
        void aRunsLocationUsesThePublicAddress() throws Exception {
            ObjectNode doc = TestPipelines.sequential("FWD", "api1");
            String code = PublishedFixtures.uniqueCode("FWD");
            doc.put("code", code);
            PublishedFixtures.publish(jdbc, doc, 1);
            HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/api/v1/pipelines/" + code + "/runs?mode=async"))
                    .header("Authorization", "Bearer " + TestJwt.allRoles())
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", "apiorch.test.example.internal")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
            String runId = com.crisil.apiorch.definition.DslJsonMapper.get().readTree(res.body()).path("runId").asText();
            assertThat(res.headers().firstValue("Location"))
                    .hasValue("https://apiorch.test.example.internal/api/v1/runs/" + runId);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @Import(TestEndpoints.class)
    class FromAnUntrustedAddress {

        @Value("${local.server.port}") int port;

        @Test
        void forwardedHeadersAreIgnored() throws Exception {
            HttpResponse<String> res = postAccepted(port);
            assertThat(res.headers().firstValue("Location")).hasValue("http://127.0.0.1:" + port + "/api/v1/runs/r-1");
        }

        @Test
        void actuatorIsNotOnTheApiPort() throws Exception {
            HttpResponse<String> res = new Http(port).get("/actuator/health");
            assertThat(res.statusCode()).isEqualTo(404);
            assertThat(res.body()).contains("urn:apiorch:problem:not-found");
        }
    }
}
