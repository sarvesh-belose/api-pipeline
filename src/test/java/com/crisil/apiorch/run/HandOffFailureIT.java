package com.crisil.apiorch.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.crisil.apiorch.engine.Lease;

import testsupport.engine.PublishedFixtures;
import testsupport.engine.StubExecutorConfig;
import testsupport.engine.TestPipelines;
import testsupport.web.Http;

/** US-4.1 AC7: when the hand-off fails after commit, the run is released with a wake-up for recovery. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(StubExecutorConfig.class)
class HandOffFailureIT {

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean RunCoordinator coordinator;

    @Test
    void failedHandOffReleasesTheRunWithAWakeUp() throws Exception {
        ObjectNode doc = TestPipelines.onboardV1();
        String code = PublishedFixtures.uniqueCode("HANDOFF");
        doc.put("code", code);
        PublishedFixtures.publish(jdbc, doc, 1);
        doThrow(new IllegalStateException("coordinator unavailable")).when(coordinator).start(any(Lease.class));

        HttpResponse<String> res = new Http(port).postJson("/api/v1/pipelines/" + code + "/runs?mode=async", "{}");
        assertThat(res.statusCode()).isEqualTo(202);
        String runId = json.readTree(res.body()).path("runId").asText();
        Map<String, Object> row = jdbc.sql("SELECT status, owner_instance, wakeup_requested FROM pipeline_run WHERE id = :id")
                .param("id", runId).query().singleRow();
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("owner_instance")).isNull();
        assertThat(row.get("wakeup_requested")).isIn(true, 1, (byte) 1);
    }
}
