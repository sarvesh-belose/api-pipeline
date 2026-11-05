package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.security.Roles;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Drafts;
import testsupport.web.EndpointJson;
import testsupport.web.Http;

/** US-3.3 AC7: with four-eyes on, the last editor of the draft cannot publish it; another admin can. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "apiorch.definition.require-four-eyes=true")
@ActiveProfiles("test")
class FourEyesIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @Test
    void anotherAdminMustPublish() throws Exception {
        Http maker = new Http(port).as(TestJwt.token("maker", Roles.ADMIN));
        Http checker = maker.as(TestJwt.token("checker", Roles.ADMIN));
        maker.postJson("/api/v1/admin/endpoints", EndpointJson.minimal(TestPipelines.ENDPOINT, "https://demo.example.com"));
        String code = PublishedFixtures.uniqueCode("FOUR");
        ObjectNode doc = TestPipelines.onboardV1();
        doc.put("code", code);
        Drafts.save(maker, doc);

        HttpResponse<String> own = Drafts.publish(maker, code, null);
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(json.readTree(own.body()).path("type").asText()).isEqualTo("urn:apiorch:problem:forbidden");
        assertThat(json.readTree(own.body()).path("detail").asText()).contains("four-eyes");

        HttpResponse<String> other = Drafts.publish(checker, code, null);
        assertThat(other.statusCode()).as(other.body()).isEqualTo(201);
        Map<String, Object> row = jdbc.sql("SELECT updated_by, published_by FROM pipeline_definition "
                + "WHERE code = :c AND version = 1").param("c", code).query().singleRow();
        assertThat(row).containsEntry("updated_by", "maker").containsEntry("published_by", "checker");
    }
}
