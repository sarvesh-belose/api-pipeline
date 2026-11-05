package com.crisil.apiorch.observability;

import static net.logstash.logback.argument.StructuredArguments.entries;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.config.InstanceIdentity;

/**
 * {@link ActivityRecorder} on {@code run_event} (US-7.1). The {@code seq} comes from {@code pipeline_run.event_seq},
 * incremented through {@code JdbcClient} under the caller's run lock (README §5.1 rule 2), so it is gap-free and
 * unique per run without relying on the index. {@code details} is stored as JSON of at most
 * {@value #MAX_DETAILS_BYTES} bytes.
 */
@Component
public class JdbcActivityRecorder implements ActivityRecorder {

    static final int MAX_DETAILS_BYTES = 4096;
    private static final Logger log = LoggerFactory.getLogger("com.crisil.apiorch.activity");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final String instanceId;

    public JdbcActivityRecorder(JdbcClient jdbc, ObjectMapper json, InstanceIdentity instance) {
        this.jdbc = jdbc;
        this.json = json;
        this.instanceId = instance.id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(UUID runId, EventType type, StepRef step, Map<String, ?> details, String actor) {
        int updated = jdbc.sql("UPDATE pipeline_run SET event_seq = event_seq + 1 WHERE id = :id")
                .param("id", runId).update();
        if (updated != 1) {
            throw new IllegalStateException("Run " + runId + " does not exist");
        }
        long seq = jdbc.sql("SELECT event_seq FROM pipeline_run WHERE id = :id").param("id", runId)
                .query(Long.class).single();
        Map<String, Object> safe = details == null ? Map.of() : new LinkedHashMap<>(details);
        jdbc.sql("""
                INSERT INTO run_event (run_id, seq, event_type, step_id, exec_no, attempt, occurred_at, instance_id,
                                       actor, details)
                VALUES (:run, :seq, :type, :step, :execNo, :attempt, SYSUTCDATETIME(), :instance, :actor, :details)""")
                .param("run", runId)
                .param("seq", seq)
                .param("type", type.name())
                .param("step", step != null ? step.stepId() : null)
                .param("execNo", step != null ? step.execNo() : null)
                .param("attempt", step != null ? step.attempt() : null)
                .param("instance", instanceId)
                .param("actor", actor)
                .param("details", detailsJson(safe))
                .update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                logEvent(runId, seq, type, step, safe, actor);
            }
        });
        return seq;
    }

    String detailsJson(Map<String, Object> details) {
        if (details.isEmpty()) {
            return null;
        }
        try {
            String text = json.writeValueAsString(details);
            if (text.getBytes(StandardCharsets.UTF_8).length <= MAX_DETAILS_BYTES) {
                return text;
            }
            Map<String, Object> truncated = new LinkedHashMap<>();
            truncated.put("truncated", true);
            truncated.put("keys", details.keySet());
            return json.writeValueAsString(truncated);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event details are not serialisable", e);
        }
    }

    private static void logEvent(UUID runId, long seq, EventType type, StepRef step, Map<String, Object> details,
            String actor) {
        Map<String, Object> fields = new LinkedHashMap<>(details);
        fields.put("eventSeq", seq);
        fields.put("eventRunId", runId.toString());
        if (step != null && step.stepId() != null) {
            fields.put("eventStepId", step.stepId());
        }
        if (actor != null) {
            fields.put("actor", actor);
        }
        if (type.warn()) {
            log.warn("event={}", type, entries(fields));
        } else {
            log.info("event={}", type, entries(fields));
        }
    }
}
