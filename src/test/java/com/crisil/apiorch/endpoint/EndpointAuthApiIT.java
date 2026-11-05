package com.crisil.apiorch.endpoint;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import testsupport.web.Http;

/** US-2.2 AC1, AC1b, AC2, AC7 through the endpoint API, with a file secret in a temporary secrets directory. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EndpointAuthApiIT {

    @RegisterExtension
    static WireMockExtension downstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static Path secretsDir;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) throws Exception {
        secretsDir = Files.createTempDirectory("apiorch-secrets");
        Files.writeString(secretsDir.resolve("kyc-key"), "k-777");
        registry.add("apiorch.secrets.file-dir", secretsDir::toString);
    }

    @Value("${local.server.port}") int port;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    private static String name() {
        return "auth-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String body(String name, String baseUrl, String auth) {
        return """
                {"name":"%s","baseUrl":"%s","healthPath":"/health","connectTimeoutMs":5000,"readTimeoutMs":10000,
                 "auth":%s}""".formatted(name, baseUrl, auth);
    }

    private String fileRef() {
        return "file:" + secretsDir.resolve("kyc-key").toString().replace("\\", "/");
    }

    @Test
    void apiKeyFromAFileReferenceIsSentAndNeverReturned() throws Exception {
        downstream.stubFor(get("/svc/health").willReturn(aResponse().withStatus(200)));
        String name = name();
        String auth = "{\"type\":\"API_KEY\",\"headerName\":\"X-Api-Key\",\"secretRef\":\"" + fileRef() + "\"}";
        HttpResponse<String> created = http.postJson("/api/v1/admin/endpoints",
                body(name, "http://localhost:" + downstream.getPort() + "/svc", auth));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).contains("\"headerName\":\"X-Api-Key\"", "\"secretRef\":\"file:").doesNotContain("k-777");
        assertThat(http.get("/api/v1/admin/endpoints/" + name).body()).doesNotContain("k-777");

        assertThat(http.postJson("/api/v1/admin/endpoints/" + name + "/probe", null).body()).contains("\"reachable\":true");
        downstream.verify(getRequestedFor(urlEqualTo("/svc/health")).withHeader("X-Api-Key", equalTo("k-777")));
    }

    @Test
    void literalSecretsAreRejected() throws Exception {
        for (String auth : new String[] {
                "{\"type\":\"API_KEY\",\"headerName\":\"X-Api-Key\",\"secretRef\":\"abc123\"}",
                "{\"type\":\"BASIC\",\"username\":\"u\",\"password\":\"hunter2\"}",
                "{\"type\":\"BEARER\",\"secretRef\":\"env:APIORCH_SECRET_X\",\"secret\":\"plain\"}"}) {
            HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints", body(name(), "https://a.example.com", auth));
            assertThat(res.statusCode()).as(auth).isEqualTo(400);
            assertThat(res.body()).as(auth).contains("secrets must be references").doesNotContain("hunter2", "plain");
        }
    }

    @Test
    void referencesOutsideTheConfinementAreRejected() throws Exception {
        for (String ref : new String[] {"env:SPRING_DATASOURCE_PASSWORD", "file:/proc/self/environ",
                "file:" + secretsDir.toString().replace("\\", "/") + "/../etc/passwd"}) {
            HttpResponse<String> res = http.postJson("/api/v1/admin/endpoints",
                    body(name(), "https://a.example.com", "{\"type\":\"BEARER\",\"secretRef\":\"" + ref + "\"}"));
            assertThat(res.statusCode()).as(ref).isEqualTo(400);
            assertThat(res.body()).as(ref).contains("auth.secretRef");
        }
        HttpResponse<String> badTokenHost = http.postJson("/api/v1/admin/endpoints", body(name(), "https://a.example.com",
                "{\"type\":\"OAUTH2_CLIENT_CREDENTIALS\",\"tokenUrl\":\"https://idp.evil.org/token\",\"clientId\":\"c\","
                        + "\"clientSecretRef\":\"env:APIORCH_SECRET_C\"}"));
        assertThat(badTokenHost.statusCode()).isEqualTo(400);
        assertThat(badTokenHost.body()).contains("auth.tokenUrl");
    }

    @Test
    void hostChangeMustResupplyTheReferences() throws Exception {
        String name = name();
        String auth = "{\"type\":\"BEARER\",\"secretRef\":\"" + fileRef() + "\"}";
        http.postJson("/api/v1/admin/endpoints", body(name, "https://a.example.com", auth));
        String moved = """
                {"name":"%s","baseUrl":"https://b.example.com","connectTimeoutMs":5000,"readTimeoutMs":10000}"""
                .formatted(name);
        HttpResponse<String> res = http.putJson("/api/v1/admin/endpoints/" + name, moved, "\"0\"");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("re-enter secret references when the host changes");
        assertThat(http.putJson("/api/v1/admin/endpoints/" + name, body(name, "https://b.example.com", auth), "\"0\"")
                .statusCode()).isEqualTo(200);
    }
}
