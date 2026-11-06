package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.step.StepOutcome;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.StubStepExecutor;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/** US-4.4 AC4: 15 independent roots with the default {@code max-parallel-steps} of 10 never exceed 10 in flight. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class ParallelLimitIT {

    @Value("${local.server.port}") int port;
    @Value("${apiorch.run.max-parallel-steps:10}") int limit;
    @Autowired StubStepExecutor stub;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void tearDown() {
        stub.reset();
    }

    @Test
    void atMostTheLimitIsInFlight() throws Exception {
        String[] ids = new String[15];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "s" + i;
        }
        ObjectNode doc = TestPipelines.sequential("LIMIT", ids);
        doc.withArray("steps").forEach(s -> ((ObjectNode) s).putArray("dependsOn"));
        doc.put("syncWaitMs", 30000);
        String code = PublishedFixtures.uniqueCode("LIMIT");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        for (String id : ids) {
            stub.on(id, inv -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return StepOutcome.succeeded(200, JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode(), 1, 300);
            });
        }
        JsonNode run = json.readTree(new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=sync", "{}")
                .body());
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(stub.invokedStepIds()).hasSize(15);
        assertThat(peak.get()).isLessThanOrEqualTo(limit).isGreaterThan(1);
    }
}
