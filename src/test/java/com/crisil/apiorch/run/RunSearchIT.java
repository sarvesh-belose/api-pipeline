package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.security.Roles;
import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/** US-7.4 AC6, AC7: run search by pipeline, statuses, time window and correlation id, paged, owner-restricted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class RunSearchIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    @Test
    void searchFiltersPagesAndRestrictsCallers() throws Exception {
        ObjectNode doc = TestPipelines.sequential("SEARCH", "api1");
        String code = PublishedFixtures.uniqueCode("SEARCH");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        Http a = new Http(port).as(TestJwt.token("system-a", Roles.RUN));
        Http b = a.as(TestJwt.token("system-b", Roles.RUN));
        Http ops = a.as(TestJwt.token("ops-user", Roles.OPS));

        List<String> runsA = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            runsA.add(json.readTree(a.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").body())
                    .path("runId").asText());
        }
        stub.on("api1", StepOutcome.failed(500, "HTTP_ERROR", "HTTP 500", 1, 5));
        String failedB = json.readTree(b.send("POST", "/api/v1/pipelines/" + code + "/runs?mode=sync", "{}", null,
                "X-Correlation-Id", "abc-123").body()).path("runId").asText();

        JsonNode all = json.readTree(ops.get("/api/v1/runs?pipeline=" + code + "&size=2&page=0").body());
        assertThat(all.path("totalElements").asLong()).isEqualTo(4);
        assertThat(all.path("totalPages").asInt()).isEqualTo(2);
        assertThat(all.path("content")).hasSize(2);
        assertThat(all.at("/content/0/runId").asText()).isEqualTo(failedB);
        assertThat(all.at("/content/0/createdBy").asText()).isEqualTo("system-b");

        JsonNode failed = json.readTree(ops.get("/api/v1/runs?pipeline=" + code + "&status=FAILED&status=TIMED_OUT")
                .body());
        assertThat(failed.path("content").findValuesAsText("runId")).containsExactly(failedB);
        assertThat(failed.at("/content/0/errorCode").asText()).isEqualTo("STEP_FAILED");

        assertThat(json.readTree(ops.get("/api/v1/runs?correlationId=abc-123").body()).path("content")
                .findValuesAsText("runId")).containsExactly(failedB);
        assertThat(json.readTree(ops.get("/api/v1/runs?pipeline=" + code + "&from=2000-01-01T00:00:00Z"
                + "&to=2000-01-02T00:00:00Z").body()).path("totalElements").asLong()).isZero();

        JsonNode mine = json.readTree(a.get("/api/v1/runs?pipeline=" + code + "&createdBy=system-b").body());
        assertThat(mine.path("content").findValuesAsText("runId")).containsExactlyInAnyOrderElementsOf(runsA);

        HttpResponse<String> tooBig = ops.get("/api/v1/runs?size=101");
        assertThat(tooBig.statusCode()).isEqualTo(400);
    }
}
