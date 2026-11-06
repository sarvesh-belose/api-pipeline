package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
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

import com.crisil.apiorch.security.Roles;
import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-4.7 AC1–AC4, AC6 and AC8, and US-7.7 AC5: a repeat with the same {@code Idempotency-Key} and input returns the
 * existing run and calls nothing again; a different input is {@code 422}; keys are per caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class IdempotencyIT {

    private static final String KEY = "Idempotency-Key";

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private String publish(Integer syncWaitMs) {
        ObjectNode doc = TestPipelines.sequential("IDEM", "api1", "api2");
        String code = PublishedFixtures.uniqueCode("IDEM");
        doc.put("code", code);
        if (syncWaitMs != null) {
            doc.put("syncWaitMs", syncWaitMs);
        }
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private Http caller(String subject) {
        return new Http(port).as(TestJwt.token(subject, Roles.RUN));
    }

    private static HttpResponse<String> start(Http http, String code, String mode, String body, String key)
            throws Exception {
        return http.send("POST", "/api/v1/pipelines/" + code + "/runs?mode=" + mode, body, null, KEY, key);
    }

    private long runs(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM pipeline_run WHERE pipeline_code = :c").param("c", code)
                .query(Long.class).single();
    }

    @Test
    void sameKeyAndInputReturnsTheExistingRun() throws Exception {
        String code = publish(null);
        Http http = caller("system-a");
        HttpResponse<String> first = start(http, code, "sync", "{\"name\":\"Asha\",\"id\":1}", "ORD-1001");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        String runId = json.readTree(first.body()).path("runId").asText();

        HttpResponse<String> again = start(http, code, "sync", "{ \"id\": 1, \"name\": \"Asha\" }", "ORD-1001");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(again.body()).path("runId").asText()).isEqualTo(runId);
        assertThat(json.readTree(again.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(runs(code)).isEqualTo(1);
        assertThat(stub.invokedStepIds()).containsExactly("api1", "api2");

        // US-7.7 AC5: the replay's response is logged too, flagged in the event
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_exchange WHERE run_id = :id AND kind = 'CALLER_RESPONSE'")
                .param("id", UUID.fromString(runId)).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT details FROM run_event WHERE run_id = :id AND event_type = 'CALLER_RESPONSE_SENT' "
                + "ORDER BY seq").param("id", UUID.fromString(runId)).query(String.class).list().get(1))
                .contains("\"replay\":true").contains("\"final\":false");

        HttpResponse<String> async = start(http, code, "async", "{\"name\":\"Asha\",\"id\":1}", "ORD-1001");
        assertThat(async.statusCode()).isEqualTo(202);
        assertThat(async.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(async.body()).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void aSyncReplayWaitsOnTheRunningRunLikeTheOriginal() throws Exception {
        String code = publish(300);
        Http http = caller("system-a");
        stub.hold("api1");
        String body = "{\"name\":\"x\"}";
        String runId = json.readTree(start(http, code, "async", body, "ORD-2").body()).path("runId").asText();

        HttpResponse<String> waiting = start(http, code, "sync", body, "ORD-2");
        assertThat(waiting.statusCode()).isEqualTo(202);
        assertThat(waiting.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(waiting.body()).path("runId").asText()).isEqualTo(runId);

        stub.release("api1");
        HttpResponse<String> done = start(http, code, "sync", body, "ORD-2");
        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(json.readTree(done.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(runs(code)).isEqualTo(1);
    }

    @Test
    void aDifferentInputIsRejectedNamingTheRun() throws Exception {
        String code = publish(null);
        Http http = caller("system-a");
        String runId = json.readTree(start(http, code, "sync", "{\"name\":\"a\"}", "ORD-3").body()).path("runId")
                .asText();
        HttpResponse<String> reuse = start(http, code, "sync", "{\"name\":\"b\"}", "ORD-3");
        assertThat(reuse.statusCode()).isEqualTo(422);
        assertThat(reuse.body()).contains("urn:apiorch:problem:idempotency-key-reuse").contains(runId);
        assertThat(runs(code)).isEqualTo(1);
    }

    @Test
    void anotherCallerWithTheSameKeyGetsItsOwnRun() throws Exception {
        String code = publish(null);
        String body = "{\"name\":\"a\"}";
        String a = json.readTree(start(caller("system-a"), code, "sync", body, "ORD-4").body()).path("runId").asText();
        HttpResponse<String> other = start(caller("system-b"), code, "sync", "{\"name\":\"other\"}", "ORD-4");
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(other.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(other.body()).doesNotContain(a);
        assertThat(runs(code)).isEqualTo(2);
    }

    @Test
    void aReplayOfAFailedRunReturnsItAndCallsNothing() throws Exception {
        String code = publish(null);
        stub.on("api1", StepOutcome.failed(500, "HTTP_ERROR", "boom", 1, 5));
        Http http = caller("system-a");
        String body = "{\"name\":\"a\"}";
        String runId = json.readTree(start(http, code, "sync", body, "ORD-5").body()).path("runId").asText();
        int calls = stub.invocations().size();

        HttpResponse<String> replay = start(http, code, "sync", body, "ORD-5");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        JsonNode resource = json.readTree(replay.body());
        assertThat(resource.path("runId").asText()).isEqualTo(runId);
        assertThat(resource.path("status").asText()).isEqualTo("FAILED");
        assertThat(stub.invocations()).hasSize(calls);
    }

    @Test
    void malformedKeysAreRejectedAndNoKeyAlwaysStartsARun() throws Exception {
        String code = publish(null);
        Http http = caller("system-a");
        assertThat(start(http, code, "sync", "{}", "x".repeat(129)).statusCode()).isEqualTo(400);
        assertThat(start(http, code, "sync", "{}", "ORD 1").statusCode()).isEqualTo(400);
        assertThat(start(http, code, "sync", "{}", "a.b_c:d-1").statusCode()).isEqualTo(200);
        assertThat(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").statusCode()).isEqualTo(200);
        assertThat(http.postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}").statusCode()).isEqualTo(200);
        assertThat(runs(code)).isEqualTo(3);
    }
}
