package com.crisil.apiorch.observability;

/** The fixed MDC key names used by every story (US-1.3). Micrometer Tracing fills {@code traceId} and {@code spanId}. */
public final class MdcKeys {

    public static final String CORRELATION_ID = "correlationId";
    public static final String RUN_ID = "runId";
    public static final String PIPELINE = "pipeline";
    public static final String VERSION = "version";
    public static final String STEP_ID = "stepId";
    public static final String ATTEMPT = "attempt";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    private MdcKeys() {
    }
}
