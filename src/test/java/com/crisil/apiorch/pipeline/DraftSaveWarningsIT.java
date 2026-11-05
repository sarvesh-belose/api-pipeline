package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import testsupport.web.Http;

/** US-3.1 AC4 and AC8: semantic problems do not block a save; they come back in the validation block. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DraftSaveWarningsIT {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;

    @Test
    void semanticProblemsAreReportedButTheDraftIsSaved() throws Exception {
        Http http = new Http(port);
        HttpResponse<String> res = http.postJson("/api/v1/admin/pipelines", """
                {"code":"WARN_PIPELINE","definition":{
                  "dslVersion": 9, "code":"WARN_PIPELINE",
                  "steps":[
                    {"id":"a","endpoint":"not-registered","method":"GET","path":"/a","dependsOn":["ghost"]},
                    {"id":"b","endpoint":"not-registered","method":"GET","path":"/b"}
                  ]}}""");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        JsonNode validation = json.readTree(res.body()).path("validation");
        assertThat(validation.path("valid").asBoolean()).isFalse();
        assertThat(validation.path("errors").findValuesAsText("code")).contains("DSL_VERSION_UNSUPPORTED",
                "UNKNOWN_DEPENDENCY", "UNKNOWN_ENDPOINT");
        assertThat(validation.path("errors").findValuesAsText("path")).contains("/steps/0/dependsOn/0", "/dslVersion");
        assertThat(http.get("/api/v1/admin/pipelines/WARN_PIPELINE/draft").statusCode()).isEqualTo(200);
    }
}
