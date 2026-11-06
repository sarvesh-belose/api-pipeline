package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

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

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/** US-7.7 AC1 and AC7: the caller's request in {@code run_exchange}, masked, with its true size and event. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class InboundExchangeIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private String publish(String capture) {
        ObjectNode doc = TestPipelines.sequential("EXI", "api1");
        String code = PublishedFixtures.uniqueCode("EXI");
        doc.put("code", code);
        ObjectNode logging = doc.putObject("logging");
        logging.put("capture", capture);
        logging.putArray("maskKeys").add("pan");
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private Map<String, Object> row(UUID runId, String kind) {
        return jdbc.sql("SELECT * FROM run_exchange WHERE run_id = :id AND kind = :kind")
                .param("id", runId).param("kind", kind).query().singleRow();
    }

    @Test
    void requestIsStoredMaskedWithItsTrueSizeAndEvent() throws Exception {
        String code = publish("FULL");
        String body = "{\"name\":\"Asha\",  \"pan\":\"ABCDE1234F\"}";
        UUID runId = UUID.fromString(json.readTree(new Http(port)
                .postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", body).body()).path("runId").asText());
        Map<String, Object> row = row(runId, "INBOUND_REQUEST");

        assertThat(json.readTree((String) row.get("body")))
                .isEqualTo(json.readTree("{\"name\":\"Asha\",\"pan\":\"***\"}"));
        JsonNode headers = json.readTree((String) row.get("headers"));
        String auth = headers.has("authorization") ? headers.path("authorization").asText()
                : headers.path("Authorization").asText();
        assertThat(auth).isEqualTo("***");
        assertThat(((Number) row.get("bytes")).longValue()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        long seq = ((Number) row.get("event_seq")).longValue();
        assertThat(jdbc.sql("SELECT event_type FROM run_event WHERE run_id = :id AND seq = :seq")
                .param("id", runId).param("seq", seq).query(String.class).single())
                .isEqualTo("INBOUND_REQUEST_RECEIVED");
        assertThat(String.valueOf(jdbc.sql("SELECT details FROM run_event WHERE run_id = :id").param("id", runId)
                .query(String.class).list())).doesNotContain("ABCDE1234F");
    }

    @Test
    void metadataKeepsStatusAndBytesOnly() throws Exception {
        String code = publish("METADATA");
        String body = "{\"name\":\"Asha\",\"pan\":\"ABCDE1234F\"}";
        UUID runId = UUID.fromString(json.readTree(new Http(port)
                .postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", body).body()).path("runId").asText());
        Map<String, Object> inbound = row(runId, "INBOUND_REQUEST");
        assertThat(inbound.get("headers")).isNull();
        assertThat(inbound.get("body")).isNull();
        assertThat(((Number) inbound.get("bytes")).longValue()).isEqualTo(body.length());

        Map<String, Object> response = row(runId, "CALLER_RESPONSE");
        assertThat(response.get("http_status")).isEqualTo(200);
        assertThat(response.get("headers")).isNull();
        assertThat(response.get("body")).isNull();
        assertThat(((Number) response.get("bytes")).longValue()).isPositive();
    }
}
