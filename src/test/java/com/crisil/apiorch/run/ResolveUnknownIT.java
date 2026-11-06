package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.engine.StepStatus;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.security.Roles;

import testsupport.engine.HttpPipelines;
import testsupport.engine.PublishedFixtures;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-5.7 AC5 and AC6 with the US-5.5 crash fixture: a POST that a dead node may have sent becomes {@code UNKNOWN} and
 * fails the run; an operator resolves it {@code SUCCEEDED} with its body and the run stays {@code FAILED}; resolving
 * again, or a step that is not {@code UNKNOWN}, is {@code 409}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apiorch.recovery.sweep-interval-ms=300", "apiorch.lease.renew-interval-ms=300",
        "spring.datasource.url=jdbc:h2:mem:resolve-it;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class ResolveUnknownIT {

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired RunRows rows;

    @Test
    void anOperatorResolvesAnUnknownStep() throws Exception {
        Http http = new Http(port);
        String endpoint = HttpPipelines.endpoint(http, "http://localhost:1");
        String code = PublishedFixtures.uniqueCode("RESOLVE");
        ObjectNode doc = HttpPipelines.pipeline(code);
        HttpPipelines.step(doc, "api1", endpoint, "POST", "/a").put("body", "{}");
        HttpPipelines.step(doc, "api2", endpoint, "POST", "/kyc").put("body", "{}");
        PublishedFixtures.publish(jdbc, doc, 1);
        UUID id = UUID.randomUUID();
        rows.insertRun(id, code, 1, "ASYNC", "c-resolve", "system-a", "dead-node", -1000);
        rows.insertStep(id, "api1", "SYNC");
        rows.insertStep(id, "api2", "SYNC");
        rows.putContext(id, "input", json.createObjectNode());
        rows.markRunning(id, 60000);
        rows.finishStep(id, "api1", StepStatus.SUCCEEDED, 1, 200, 5L, null, null, false, null);
        rows.markStepRunning(id, "api2");
        await().atMost(Duration.ofSeconds(15)).until(() -> rows.run(id).orElseThrow().status().terminal());
        assertThat(rows.run(id).orElseThrow().status().name()).isEqualTo("FAILED");

        Http ops = http.as(TestJwt.token("ops-user", Roles.OPS));
        String path = "/api/v1/runs/" + id + "/steps/api2/resolve";
        assertThat(ops.postJson(path, "{\"outcome\":\"SUCCEEDED\"}").statusCode()).isEqualTo(400);
        assertThat(ops.postJson("/api/v1/runs/" + id + "/steps/api1/resolve",
                "{\"outcome\":\"SUCCEEDED\",\"reason\":\"x\"}").statusCode()).isEqualTo(409);

        HttpResponse<String> resolved = ops.postJson(path,
                "{\"outcome\":\"SUCCEEDED\",\"body\":{\"id\":\"K-9\"},\"httpStatus\":201,\"reason\":\"Confirmed in KYC console\"}");
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);
        assertThat(json.readTree(resolved.body()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(rows.context(id).get("api2").at("/body/id").asText()).isEqualTo("K-9");
        assertThat(rows.run(id).orElseThrow().status().name()).isEqualTo("FAILED");
        JsonNode details = json.readTree(jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = "
                + "'STEP_RESOLVED'").param("id", id).query(String.class).single());
        assertThat(details.path("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(details.path("reason").asText()).isEqualTo("Confirmed in KYC console");
        assertThat(jdbc.sql("SELECT actor FROM run_event WHERE run_id = :id AND event_type = 'STEP_RESOLVED'")
                .param("id", id).query(String.class).single()).isEqualTo("ops-user");

        assertThat(ops.postJson(path, "{\"outcome\":\"FAILED\",\"reason\":\"again\"}").statusCode()).isEqualTo(409);
    }
}
