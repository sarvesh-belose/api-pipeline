package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.MeterRegistry;

import com.crisil.apiorch.config.InstanceIdentity;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.security.Roles;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.security.TestJwt;
import testsupport.web.Http;

/**
 * US-5.4 AC3, AC5 and AC7 with {@code max-in-flight-runs=2}, on its own database: a third new run gets
 * {@code 429 admission-rejected} with no run row; a replay is still served; runs recovered at the limit are claimed
 * anyway; and once everything ends the count is exactly the leases this node holds in the database (none).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apiorch.admission.max-in-flight-runs=2", "apiorch.recovery.sweep-interval-ms=300",
        "apiorch.lease.renew-interval-ms=300",
        "spring.datasource.url=jdbc:h2:mem:admission-it;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class AdmissionControlIT {

    @Value("${local.server.port}") int port;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired AdmissionControl admission;
    @Autowired RunRows rows;
    @Autowired InstanceIdentity instance;
    @Autowired MeterRegistry meters;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    private String publish() {
        ObjectNode doc = TestPipelines.sequential("ADMIT", "api1");
        String code = PublishedFixtures.uniqueCode("ADMIT");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        return code;
    }

    private long owned() {
        return jdbc.sql("SELECT COUNT(*) FROM pipeline_run WHERE owner_instance = :me").param("me", instance.id())
                .query(Long.class).single();
    }

    private HttpResponse<String> start(Http http, String code, String key) throws Exception {
        return http.send("POST", "/api/v1/pipelines/" + code + "/runs?mode=async", "{}", null, "Idempotency-Key", key);
    }

    @Test
    void aNewRunPastTheLimitIsRefusedButAReplayIsServed() throws Exception {
        String code = publish();
        stub.hold("api1");
        Http http = new Http(port).as(TestJwt.token("system-a", Roles.RUN));
        String first = json.readTree(start(http, code, "ADM-1").body()).path("runId").asText();
        assertThat(start(http, code, "ADM-2").statusCode()).isEqualTo(202);
        double rejectedBefore = meters.counter("apiorch.admission.rejected").count();

        HttpResponse<String> refused = start(http, code, "ADM-3");
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.body()).contains("urn:apiorch:problem:admission-rejected");
        assertThat(refused.headers().firstValue("Retry-After")).contains("1");
        assertThat(meters.counter("apiorch.admission.rejected").count()).isEqualTo(rejectedBefore + 1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM pipeline_run WHERE pipeline_code = :c").param("c", code)
                .query(Long.class).single()).isEqualTo(2);

        HttpResponse<String> replay = start(http, code, "ADM-1");
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).path("runId").asText()).isEqualTo(first);

        stub.release("api1");
        await().atMost(Duration.ofSeconds(10)).until(() -> admission.driven() == 0 && owned() == 0);
        assertThat(start(http, code, "ADM-4").statusCode()).isEqualTo(202);
    }

    @Test
    void recoveredRunsAreClaimedAtTheLimitAndTheCountDoesNotDrift() throws Exception {
        String code = publish();
        stub.hold("api1");
        List<UUID> orphans = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            rows.insertRun(id, code, 1, "ASYNC", "c-admit", "system-a", "dead-node", -1000);
            rows.insertStep(id, "api1", "SYNC");
            rows.putContext(id, "input", json.createObjectNode());
            orphans.add(id);
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> owned() == 5);
        assertThat(admission.driven()).isGreaterThan(2);
        Http http = new Http(port).as(TestJwt.token("system-a", Roles.RUN));
        assertThat(start(http, code, "ADM-OVER").statusCode()).isEqualTo(429);

        stub.release("api1");
        await().atMost(Duration.ofSeconds(15)).until(() -> orphans.stream().allMatch(id ->
                rows.run(id).orElseThrow().status().terminal()));
        await().atMost(Duration.ofSeconds(5)).until(() -> admission.driven() == owned());
        assertThat(owned()).isZero();
        assertThat(admission.driven()).isZero();
    }
}
