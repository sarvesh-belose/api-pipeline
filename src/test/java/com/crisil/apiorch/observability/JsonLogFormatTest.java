package com.crisil.apiorch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;

/** US-1.3 AC4: a prod log line is one JSON object with the standard and MDC fields and a stack trace. */
class JsonLogFormatTest {

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private JsonNode encode(ILoggingEvent event) throws Exception {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        for (String key : new String[] {"correlationId", "runId", "pipeline", "version", "stepId", "attempt",
                "traceId", "spanId"}) {
            encoder.addIncludeMdcKeyName(key);
        }
        encoder.setCustomFields("{\"service\":\"apiorch-service\"}");
        encoder.start();
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(line.strip()).doesNotContain("\n");
        return json.readTree(line);
    }

    @Test
    void lineCarriesStandardAndMdcFields() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.crisil.apiorch.engine.RunCoordinator");
        UUID runId = UUID.randomUUID();
        LoggingEvent event;
        try (MdcSupport.Scope run = MdcSupport.withRun(runId, "DEMO_ONBOARD", 3, "abc-123");
                MdcSupport.Scope step = MdcSupport.withStep("api1", 1)) {
            event = new LoggingEvent(Logger.FQCN, logger, Level.INFO, "step finished", null, null);
            event.prepareForDeferredProcessing();
        }
        JsonNode line = encode(event);
        assertThat(line.has("@timestamp")).isTrue();
        assertThat(line.path("level").asText()).isEqualTo("INFO");
        assertThat(line.path("logger_name").asText()).endsWith("RunCoordinator");
        assertThat(line.has("thread_name")).isTrue();
        assertThat(line.path("message").asText()).isEqualTo("step finished");
        assertThat(line.path("correlationId").asText()).isEqualTo("abc-123");
        assertThat(line.path("runId").asText()).isEqualTo(runId.toString());
        assertThat(line.path("stepId").asText()).isEqualTo("api1");
        assertThat(line.path("attempt").asText()).isEqualTo("1");
        assertThat(line.path("service").asText()).isEqualTo("apiorch-service");
    }

    @Test
    void exceptionsCarryAStackTrace() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("x");
        LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, Level.ERROR, "failed",
                new IllegalStateException("boom"), null);
        assertThat(encode(event).path("stack_trace").asText()).contains("IllegalStateException", "boom");
    }
}
