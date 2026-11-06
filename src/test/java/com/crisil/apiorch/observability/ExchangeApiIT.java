package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

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

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/** US-7.7 AC6: {@code GET /api/v1/runs/{runId}/exchanges/{kind}} for {@code apiorch.ops}, in order, masked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class ExchangeApiIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    @Test
    void opsReadsTheExchangesInOrderAndCallersCannot() throws Exception {
        ObjectNode doc = TestPipelines.sequential("EXA", "api1");
        String code = PublishedFixtures.uniqueCode("EXA");
        doc.put("code", code);
        doc.putObject("logging").put("capture", "FULL").putArray("maskKeys").add("pan");
        PublishedFixtures.publish(jdbc, doc, 1);
        Http caller = new Http(port).as(TestJwt.token("system-a", Roles.RUN));
        String runId = json.readTree(caller.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync",
                "{\"name\":\"Asha\",\"pan\":\"ABCDE1234F\"}").body()).path("runId").asText();
        Http ops = caller.as(TestJwt.token("ops-user", Roles.OPS));

        HttpResponse<String> inbound = ops.get("/api/v1/runs/" + runId + "/exchanges/INBOUND_REQUEST");
        assertThat(inbound.statusCode()).isEqualTo(200);
        assertThat(inbound.body()).doesNotContain("ABCDE1234F");
        JsonNode in = json.readTree(inbound.body());
        assertThat(in).hasSize(1);
        assertThat(in.at("/0/kind").asText()).isEqualTo("INBOUND_REQUEST");
        assertThat(in.at("/0/body/pan").asText()).isEqualTo("***");
        assertThat(in.at("/0/payloadPurged").asBoolean()).isFalse();

        JsonNode out = json.readTree(ops.get("/api/v1/runs/" + runId + "/exchanges/CALLER_RESPONSE").body());
        assertThat(out).hasSize(1);
        assertThat(out.at("/0/httpStatus").asInt()).isEqualTo(200);
        assertThat(out.at("/0/final").asBoolean()).isTrue();
        assertThat(out.at("/0/seq").asLong()).isGreaterThan(in.at("/0/seq").asLong());

        assertThat(caller.get("/api/v1/runs/" + runId + "/exchanges/INBOUND_REQUEST").statusCode()).isEqualTo(403);
        assertThat(ops.get("/api/v1/runs/" + runId + "/exchanges/OTHER").statusCode()).isEqualTo(400);
        assertThat(ops.get("/api/v1/runs/00000000-0000-0000-0000-000000000001/exchanges/INBOUND_REQUEST")
                .statusCode()).isEqualTo(404);
    }
}
