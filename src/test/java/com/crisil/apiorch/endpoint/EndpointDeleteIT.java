package com.crisil.apiorch.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-2.1 AC5: an endpoint in use by a draft, the latest version or an unfinished run cannot be deleted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EndpointDeleteIT {

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
    }

    private String endpoint() throws Exception {
        String name = "ep-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(http.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(name, "https://a.example.com"))
                .statusCode()).isEqualTo(201);
        return name;
    }

    private String pipeline() {
        String code = "P_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.sql("INSERT INTO pipeline (code, created_by, updated_by) VALUES (:c, 't', 't')").param("c", code).update();
        return code;
    }

    private void definition(String code, int version, String content) {
        jdbc.sql("""
                INSERT INTO pipeline_definition (id, code, version, status, content, checksum, created_by, updated_by)
                VALUES (:id, :c, :v, :s, :content, :sum, 't', 't')""")
                .param("id", UUID.randomUUID()).param("c", code).param("v", version)
                .param("s", version == 0 ? "DRAFT" : "PUBLISHED").param("content", content)
                .param("sum", "0".repeat(64)).update();
    }

    private void run(String code, int version, boolean ended) {
        jdbc.sql("""
                INSERT INTO pipeline_run (id, pipeline_code, pipeline_version, status, mode, correlation_id, created_by,
                                          ended_at)
                VALUES (:id, :c, :v, :s, 'SYNC', 'c', 't', CASE WHEN :ended = 1 THEN SYSUTCDATETIME() END)""")
                .param("id", UUID.randomUUID()).param("c", code).param("v", version)
                .param("s", ended ? "COMPLETED" : "RUNNING").param("ended", ended ? 1 : 0).update();
    }

    private static String steps(String endpoint) {
        return "{\"steps\":[{\"id\":\"api1\",\"endpoint\":\"" + endpoint + "\"}]}";
    }

    private JsonNode inUse(String name) throws Exception {
        HttpResponse<String> res = http.delete("/api/v1/admin/endpoints/" + name);
        assertThat(res.statusCode()).isEqualTo(409);
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("type").asText()).isEqualTo("urn:apiorch:problem:endpoint-in-use");
        return body.path("references");
    }

    @Test
    void unreferencedEndpointIsDeleted() throws Exception {
        String name = endpoint();
        assertThat(http.delete("/api/v1/admin/endpoints/" + name).statusCode()).isEqualTo(204);
        assertThat(http.get("/api/v1/admin/endpoints/" + name).statusCode()).isEqualTo(404);
        assertThat(http.delete("/api/v1/admin/endpoints/" + name).statusCode()).isEqualTo(404);
    }

    @Test
    void draftReferenceBlocksDelete() throws Exception {
        String name = endpoint();
        definition(pipeline(), 0, steps(name));
        JsonNode refs = inUse(name);
        assertThat(refs.findValuesAsText("source")).containsExactly("DRAFT");
    }

    @Test
    void latestVersionAndCompensateEndpointBlockDelete() throws Exception {
        String name = endpoint();
        String code = pipeline();
        definition(code, 1, steps("other"));
        definition(code, 2, "{\"steps\":[{\"id\":\"api2\",\"endpoint\":\"other\",\"compensate\":{\"endpoint\":\""
                + name + "\"}}]}");
        JsonNode refs = inUse(name);
        assertThat(refs.findValuesAsText("field")).containsExactly("compensate.endpoint");
        assertThat(refs.findValuesAsText("source")).containsExactly("LATEST");
        assertThat(refs.findValuesAsText("version")).containsExactly("2");
    }

    @Test
    void onlyUnfinishedRunsPinOlderVersions() throws Exception {
        String name = endpoint();
        String code = pipeline();
        definition(code, 1, steps(name));
        definition(code, 2, steps("other"));
        run(code, 1, true);
        assertThat(http.delete("/api/v1/admin/endpoints/" + name).statusCode()).isEqualTo(204);

        String second = endpoint();
        String code2 = pipeline();
        definition(code2, 1, steps(second));
        definition(code2, 2, steps("other"));
        run(code2, 1, false);
        assertThat(inUse(second).findValuesAsText("source")).containsExactly("IN_FLIGHT_RUN");
    }
}
