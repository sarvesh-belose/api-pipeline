package com.crisil.apiorch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import testsupport.web.Http;
import testsupport.web.TestEndpoints;

/** US-1.1 AC5: per-path body limits answer 413 for declared and chunked bodies. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apiorch.definition.max-document-bytes=2048",
        "apiorch.run.max-input-bytes=4096",
        "apiorch.callback.max-body-bytes=3072"})
@ActiveProfiles("test")
@Import(TestEndpoints.class)
class RequestSizeLimitIT {

    @Value("${local.server.port}") int port;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    private static int limitFor(String path) {
        return path.startsWith("/api/v1/admin") ? 2048 : path.startsWith("/api/v1/pipelines") ? 4096 : 3072;
    }

    private static void assertTooLarge(HttpResponse<String> res) {
        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.body()).contains("urn:apiorch:problem:payload-too-large");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/test/free", "/api/v1/pipelines/demo/runs", "/api/v1/callbacks/r1/s1"})
    void bodyAtTheLimitIsAccepted(String path) throws Exception {
        assertThat(http.postJson(path, Http.jsonOfSize(limitFor(path) - 50)).statusCode()).isEqualTo(200);
        assertThat(http.postChunked(path, Http.jsonOfSize(limitFor(path) - 50)).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/test/free", "/api/v1/pipelines/demo/runs", "/api/v1/callbacks/r1/s1"})
    void declaredLengthOverTheLimitIsRejected(String path) throws Exception {
        assertTooLarge(http.postJson(path, Http.jsonOfSize(limitFor(path) + 100)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/test/free", "/api/v1/pipelines/demo/runs", "/api/v1/callbacks/r1/s1"})
    void chunkedBodyOverTheLimitIsRejected(String path) throws Exception {
        assertTooLarge(http.postChunked(path, Http.jsonOfSize(limitFor(path) + 100)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/test/typed"})
    void chunkedTypedBodyOverTheLimitIsRejected(String path) throws Exception {
        assertTooLarge(http.postChunked(path, "{\"name\":\"" + "x".repeat(3000) + "\"}"));
    }
}
