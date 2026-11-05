package com.crisil.apiorch.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-2.1 AC1–AC3 and AC7: CRUD, ETag and If-Match, validation and the effective configuration. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EndpointApiIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode body(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body());
    }

    @Test
    void createReturns201WithLocationAndETag() throws Exception {
        String name = unique("kyc-svc");
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", EndpointJson.full(name,
                "https://kyc.internal.example.com/api"));
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.headers().firstValue("Location")).hasValue("/api/v1/admin/endpoints/" + name);
        assertThat(res.headers().firstValue("ETag")).hasValue("\"0\"");
        JsonNode body = body(res);
        assertThat(body.path("createdBy").asText()).isEqualTo("anonymous");
        assertThat(body.path("version").asLong()).isZero();
        assertThat(body.at("/resilience/circuitBreaker/slidingWindowSize").asInt()).isEqualTo(40);
        assertThat(body.at("/resilience/circuitBreaker/failureRateThreshold").asInt()).isEqualTo(50);
        assertThat(body.at("/resilience/circuitBreaker/inherited").asBoolean()).isFalse();
        assertThat(body.at("/resilience/bulkhead/maxConcurrentCalls").asInt()).isEqualTo(25);
        assertThat(body.at("/resilience/bulkhead/inherited").asBoolean()).isTrue();
        assertThat(body.at("/resilience/retry/backoff").asText()).isEqualTo("EXPONENTIAL");
        assertThat(body.at("/auth/type").asText()).isEqualTo("NONE");
    }

    @Test
    void getAndListReturnTheEffectiveConfiguration() throws Exception {
        String name = unique("customer-svc");
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name, "https://customer.example.com"));
        HttpResponse<String> one = http.get("/api/v1/admin/endpoints/" + name);
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(one.headers().firstValue("ETag")).hasValue("\"0\"");
        assertThat(body(one).path("httpVersion").asText()).isEqualTo("HTTP_1_1");
        assertThat(body(one).path("enabled").asBoolean()).isTrue();
        assertThat(body(one).at("/resilience/retry/inherited").asBoolean()).isTrue();
        assertThat(body(http.get("/api/v1/admin/endpoints")).findValuesAsText("name")).contains(name);
        assertThat(http.get("/api/v1/admin/endpoints/does-not-exist").body())
                .contains("urn:apiorch:problem:endpoint-not-found");
    }

    @Test
    void duplicateNameIsAlreadyExists() throws Exception {
        String name = unique("dup");
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name, "https://a.example.com"));
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name,
                "https://b.example.com"));
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("urn:apiorch:problem:already-exists");
    }

    @Test
    void everyInvalidFieldIsListed() throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", """
                {"name":"Bad_Name","baseUrl":"https://user:pw@evil.org/x?q=1#f","healthPath":"health/../x",
                 "connectTimeoutMs":50,"readTimeoutMs":500000,
                 "resilience":{"circuitBreaker":{"slidingWindowSize":10,"minimumCalls":20},
                               "retry":{"initialMs":5000,"maxMs":100}}}""");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(body(res).path("type").asText()).isEqualTo("urn:apiorch:problem:validation-failed");
        assertThat(body(res).path("errors").findValuesAsText("field")).contains("name", "baseUrl", "healthPath",
                "connectTimeoutMs", "readTimeoutMs", "resilience.circuitBreaker.minimumCalls",
                "resilience.retry.initialMs");
        assertThat(res.body()).contains("userinfo", "query", "fragment", "not in apiorch.endpoints.allowed-hosts");
    }

    @Test
    void ipLiteralHostIsRejectedOutsideLocal() throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints",
                EndpointJson.minimal(unique("ip"), "http://127.0.0.1:8080"));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("IP address");
    }

    @Test
    void misspeltPropertyIsRejectedNotDefaulted() throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", """
                {"name":"%s","baseUrl":"https://a.example.com","connectTimeoutMs":2000,"readTimeoutMs":5000,
                 "resilence":{"retry":{"maxAttempts":1}}}""".formatted(unique("typo")));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("resilence");
    }

    @Test
    void incompleteAuthIsRejected() throws Exception {
        HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", """
                {"name":"%s","baseUrl":"https://a.example.com","connectTimeoutMs":2000,"readTimeoutMs":5000,
                 "auth":{"type":"BEARER","username":"x"}}""".formatted(unique("auth")));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("auth.secretRef", "auth.username");
    }

    @Test
    void updateNeedsTheCurrentVersion() throws Exception {
        String name = unique("upd");
        String path = "/api/v1/admin/endpoints/" + name;
        http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name, "https://a.example.com"));
        String changed = EndpointJson.minimal(name, "https://b.example.com");

        HttpResponse<String> missing = http.putJson(path, changed, null);
        assertThat(missing.statusCode()).isEqualTo(428);
        assertThat(missing.body()).contains("urn:apiorch:problem:precondition-required");

        HttpResponse<String> ok = http.putJson(path, changed, "\"0\"");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("ETag")).hasValue("\"1\"");
        assertThat(body(ok).path("baseUrl").asText()).isEqualTo("https://b.example.com");

        HttpResponse<String> stale = http.putJson(path, EndpointJson.minimal(name, "https://c.example.com"), "\"0\"");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("urn:apiorch:problem:version-conflict");

        HttpResponse<String> weak = http.putJson(path, EndpointJson.minimal(name, "https://c.example.com"), "W/\"1\"");
        assertThat(weak.statusCode()).isEqualTo(200);

        assertThat(http.putJson(path, changed, "nonsense").statusCode()).isEqualTo(400);
        assertThat(http.putJson(path, EndpointJson.minimal("other-name", "https://a.example.com"), "\"2\"")
                .statusCode()).isEqualTo(400);
        assertThat(http.putJson("/api/v1/admin/endpoints/" + unique("nope"),
                EndpointJson.minimal("x", "https://a.example.com"), "\"0\"").statusCode()).isEqualTo(400);
    }
}
