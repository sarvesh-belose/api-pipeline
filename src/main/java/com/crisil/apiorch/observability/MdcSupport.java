package com.crisil.apiorch.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;

/**
 * Try-with-resources MDC scopes (US-1.3). A scope sets its keys and, on close, restores each key to the value it had
 * before, so nested scopes and pooled threads never leak run or step context.
 *
 * <pre>{@code
 * try (MdcSupport.Scope run = MdcSupport.withRun(runId, "DEMO_ONBOARD", 3, correlationId);
 *      MdcSupport.Scope step = MdcSupport.withStep("api1", 1)) { ... }
 * }</pre>
 */
public final class MdcSupport {

    private MdcSupport() {
    }

    public static Scope withRun(UUID runId, String pipeline, int version, String correlationId) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MdcKeys.RUN_ID, String.valueOf(runId));
        values.put(MdcKeys.PIPELINE, pipeline);
        values.put(MdcKeys.VERSION, String.valueOf(version));
        if (correlationId != null) {
            values.put(MdcKeys.CORRELATION_ID, correlationId);
        }
        return new Scope(values);
    }

    public static Scope withStep(String stepId, int attempt) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MdcKeys.STEP_ID, stepId);
        values.put(MdcKeys.ATTEMPT, String.valueOf(attempt));
        return new Scope(values);
    }

    /** Restores the keys it set to their previous values when closed. */
    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous = new LinkedHashMap<>();

        private Scope(Map<String, String> values) {
            values.forEach((key, value) -> {
                previous.put(key, MDC.get(key));
                MDC.put(key, value);
            });
        }

        @Override
        public void close() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
