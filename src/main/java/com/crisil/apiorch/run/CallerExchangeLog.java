package com.crisil.apiorch.run;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.observability.RunExchangeWriter;
import com.crisil.apiorch.observability.RunExchangeWriter.Exchange;
import com.crisil.apiorch.persistence.JdbcRunLeaseService;
import com.crisil.apiorch.persistence.RunRows;
import com.crisil.apiorch.run.dto.RunResource;

/**
 * The caller side of the activity log (US-7.7): the inbound request, every response to the start request, and the
 * one {@code final = 1} row holding the run's final result. That row is the first response sent once the run is
 * terminal; when no response is sent then (the caller got {@code 202} and no request is waiting on this node) the
 * finalizer writes it, with no HTTP status. Responses are decided and recorded under the run lock, so exactly one
 * final row exists.
 */
@Component
public class CallerExchangeLog {

    private static final Logger log = LoggerFactory.getLogger(CallerExchangeLog.class);

    private final RunExchangeWriter writer;
    private final CapturePolicies captures;
    private final PublishedPipelines pipelines;
    private final RunResourceAssembler assembler;
    private final RunCompletionNotifier notifier;
    private final JdbcRunLeaseService leases;
    private final RunRows rows;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public CallerExchangeLog(RunExchangeWriter writer, CapturePolicies captures, PublishedPipelines pipelines,
            RunResourceAssembler assembler, RunCompletionNotifier notifier, JdbcRunLeaseService leases, RunRows rows,
            TransactionTemplate tx, ObjectMapper json) {
        this.writer = writer;
        this.captures = captures;
        this.pipelines = pipelines;
        this.assembler = assembler;
        this.notifier = notifier;
        this.leases = leases;
        this.rows = rows;
        this.tx = tx;
        this.json = json;
    }

    /** {@code INBOUND_REQUEST}, in the run-creation transaction (AC1). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void inbound(UUID runId, String code, int version, Map<String, String> headers, JsonNode body, long bytes) {
        String text = body.toString();
        writer.write(runId, new Exchange(RunExchangeWriter.INBOUND_REQUEST, null, headers, text,
                MediaType.APPLICATION_JSON_VALUE, bytes, false),
                captures.forPipeline(pipelines.compiled(code, version).definition()), null);
    }

    /**
     * Builds the response under the run lock and records it as {@code CALLER_RESPONSE}; it is the final row when the
     * run is terminal and has none yet (AC2–AC5). A failed write is logged and the response is still sent.
     */
    public ResponseEntity<RunResource> respond(UUID runId, Supplier<ResponseEntity<RunResource>> build, boolean replay) {
        try {
            return tx.execute(s -> {
                leases.lockUnowned(runId);
                ResponseEntity<RunResource> response = build.get();
                RunRows.Run run = rows.run(runId).orElseThrow();
                boolean last = run.status().terminal() && !writer.hasFinal(runId);
                Map<String, Object> details = new LinkedHashMap<>();
                if (replay) {
                    details.put("replay", true);
                }
                write(run, response.getStatusCode().value(), headers(response.getHeaders()), response.getBody(), last,
                        details);
                return response;
            });
        } catch (RuntimeException e) {
            log.error("caller response not recorded runId={}", runId, e);
            return build.get();
        }
    }

    /**
     * In the finalizing transaction: the {@code final = 1} row with the masked run resource, unless a request on
     * this node is still waiting and will send (and record) it (AC3).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finalResult(UUID runId) {
        if (notifier.isWaiting(runId) || writer.hasFinal(runId)) {
            return;
        }
        RunRows.Run run = rows.run(runId).orElseThrow();
        write(run, null, null, assembler.assemble(run), true, null);
    }

    private void write(RunRows.Run run, Integer status, Map<String, String> headers, RunResource body, boolean last,
            Map<String, ?> details) {
        String text;
        try {
            text = json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Run resource is not serialisable", e);
        }
        writer.write(run.id(), new Exchange(RunExchangeWriter.CALLER_RESPONSE, status, headers, text,
                MediaType.APPLICATION_JSON_VALUE, text.getBytes(StandardCharsets.UTF_8).length, last),
                captures.forPipeline(pipelines.compiled(run.pipelineCode(), run.pipelineVersion()).definition()),
                details);
    }

    private static Map<String, String> headers(HttpHeaders headers) {
        Map<String, String> out = new LinkedHashMap<>(headers.toSingleValueMap());
        out.putIfAbsent(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return out;
    }
}
