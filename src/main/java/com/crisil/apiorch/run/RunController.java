package com.crisil.apiorch.run;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.config.ExecutorConfig;
import com.crisil.apiorch.config.RequestSizeLimitFilter;
import com.crisil.apiorch.engine.RunStatus;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.CorrelationIdFilter;
import com.crisil.apiorch.observability.ProblemTypes;
import com.crisil.apiorch.run.dto.ExchangeView;
import com.crisil.apiorch.run.dto.RunResource;
import com.crisil.apiorch.run.dto.Timeline;

/**
 * {@code POST /api/v1/pipelines/{code}/runs} (US-4.1 sync, US-4.2 async) and {@code GET /api/v1/runs/{runId}}. Sync
 * requests return a {@code CompletableFuture}: the Tomcat thread is released while the run executes (AC8).
 */
@RestController
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    private static final String RESPOND_ASYNC = "respond-async";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final RunService runs;
    private final RunQueryService queries;
    private final RunResourceAssembler assembler;
    private final CallerExchangeLog exchanges;
    private final TimelineService timelines;
    private final RunOperatorService operators;
    private final ThreadPoolTaskExecutor responses;

    public RunController(RunService runs, RunQueryService queries, RunResourceAssembler assembler,
            CallerExchangeLog exchanges, TimelineService timelines, RunOperatorService operators,
            @Qualifier(ExecutorConfig.RUN_RESPONSE) ThreadPoolTaskExecutor responses) {
        this.runs = runs;
        this.queries = queries;
        this.assembler = assembler;
        this.exchanges = exchanges;
        this.timelines = timelines;
        this.operators = operators;
        this.responses = responses;
    }

    @PostMapping("/api/v1/pipelines/{code}/runs")
    public CompletableFuture<ResponseEntity<RunResource>> start(@PathVariable String code,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "version", required = false) Integer version,
            @RequestHeader(name = "Prefer", required = false) String prefer,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody JsonNode input, HttpServletRequest request, Authentication caller) {
        boolean preferAsync = mode == null && prefersAsync(prefer);
        boolean sync = switch (mode == null ? (preferAsync ? "async" : "sync") : mode) {
            case "sync" -> true;
            case "async" -> false;
            default -> throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "mode must be sync or async");
        };
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        RunService.Started started = runs.start(code, version, input, sync, correlationId, inbound(request, input),
                idempotencyKey, caller);
        boolean replayed = started.replayed();
        // US-1.4 AC2: the public address as the caller used it (forwarded headers are honoured only from trusted
        // proxies), taken now because a sync response is built later on another thread
        URI location = URI.create(ServletUriComponentsBuilder.fromRequest(request).replacePath(request.getContextPath())
                .replaceQuery(null).path("/api/v1/runs/{id}").buildAndExpand(started.runId()).toUriString());
        if (!sync) {
            return CompletableFuture.completedFuture(exchanges.respond(started.runId(), () -> {
                RunResource resource = replayed ? assembler.assemble(started.runId())
                        : RunResourceAssembler.accepted(started.runId(), code, started.version());
                ResponseEntity.BodyBuilder accepted = ResponseEntity.status(HttpStatus.ACCEPTED)
                        .location(location)
                        .header("X-Apiorch-Run-Id", started.runId().toString())
                        .header("X-Apiorch-Run-Status", resource.status());
                if (preferAsync) {
                    accepted.header("Preference-Applied", RESPOND_ASYNC);
                }
                if (replayed) {
                    accepted.header(REPLAYED, "true");
                }
                return accepted.body(resource);
            }, replayed));
        }
        long waitStart = System.nanoTime();
        return started.done().thenApplyAsync(done -> exchanges.respond(started.runId(),
                () -> respond(started.runId(), location, waitStart, replayed), replayed), responses);
    }

    /** US-7.7: the caller's exchange rows, oldest first ({@code apiorch.ops}); the read is recorded (US-7.4). */
    @GetMapping("/api/v1/runs/{runId}/exchanges/{kind}")
    public List<ExchangeView> exchanges(@PathVariable String runId, @PathVariable String kind, Authentication caller) {
        List<ExchangeView> rows = queries.exchanges(runId, kind);
        timelines.recordAccess(UUID.fromString(runId), kind, null, null, null, caller);
        return rows;
    }

    /** US-5.7 AC1–AC4: cancel with an audit reason ({@code apiorch.ops}); {@code 202}, completes asynchronously. */
    @PostMapping("/api/v1/runs/{runId}/cancel")
    public ResponseEntity<RunResource> cancel(@PathVariable String runId, @RequestBody JsonNode body,
            Authentication caller) {
        RunResource resource = operators.cancel(runIdOf(runId), text(body, "reason"),
                body.path("compensate").asBoolean(false), caller.getName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("X-Apiorch-Run-Id", runId)
                .header("X-Apiorch-Run-Status", resource.status()).body(resource);
    }

    /** US-5.7 AC5, AC6: resolve an {@code UNKNOWN} step ({@code apiorch.ops}); the run is not advanced. */
    @PostMapping("/api/v1/runs/{runId}/steps/{stepId}/resolve")
    public RunResource.Step resolve(@PathVariable String runId, @PathVariable String stepId, @RequestBody JsonNode body,
            Authentication caller) {
        JsonNode stepBody = body.has("body") ? body.get("body") : null;
        Integer httpStatus = body.hasNonNull("httpStatus") ? body.get("httpStatus").asInt() : null;
        return operators.resolve(runIdOf(runId), stepId, text(body, "outcome"), stepBody, httpStatus,
                text(body, "reason"), caller.getName());
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }

    private static UUID runIdOf(String runId) {
        try {
            UUID id = UUID.fromString(runId);
            if (id.toString().equalsIgnoreCase(runId)) {
                return id;
            }
        } catch (IllegalArgumentException e) {
            // reported as not found below
        }
        throw RunResourceAssembler.notFound(runId);
    }

    /** US-7.4: the run's calls in order, without bodies; the creator or {@code apiorch.ops}. */
    @GetMapping("/api/v1/runs/{runId}/timeline")
    public Timeline timeline(@PathVariable String runId, @RequestParam(name = "include", required = false) String include,
            Authentication caller) {
        return timelines.timeline(runId, "events".equals(include), caller);
    }

    /** US-7.4 AC4: one attempt's masked headers and bodies ({@code apiorch.ops}). */
    @GetMapping("/api/v1/runs/{runId}/steps/{stepId}/attempts/{n}")
    public Timeline.AttemptDetail attempt(@PathVariable String runId, @PathVariable String stepId, @PathVariable int n,
            @RequestParam(name = "kind", defaultValue = "REQUEST") String kind, Authentication caller) {
        return timelines.attempt(runId, stepId, n, kind, caller);
    }

    /** US-7.4 AC6, AC7: search, newest first. */
    @GetMapping("/api/v1/runs")
    public Timeline.Page search(@RequestParam(name = "pipeline", required = false) String pipeline,
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "from", required = false) java.time.Instant from,
            @RequestParam(name = "to", required = false) java.time.Instant to,
            @RequestParam(name = "correlationId", required = false) String correlationId,
            @RequestParam(name = "createdBy", required = false) String createdBy,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size, Authentication caller) {
        return timelines.search(pipeline, status, from, to, correlationId, createdBy, page, size, caller);
    }

    /** US-4.2: the caller's own run, or any run for {@code apiorch.ops}; polled until done. */
    @GetMapping("/api/v1/runs/{runId}")
    public ResponseEntity<RunResource> get(@PathVariable String runId, Authentication caller) {
        RunResource resource = queries.get(runId, caller);
        log.debug("run polled runId={} status={}", runId, resource.status());
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok().header("X-Apiorch-Run-Id", resource.runId().toString())
                .header("X-Apiorch-Run-Status", resource.status());
        if (!RunStatus.valueOf(resource.status()).doneForCaller()) {
            ok.header("Retry-After", "2");
        }
        return ok.body(resource);
    }

    private static RunService.Inbound inbound(HttpServletRequest request, JsonNode input) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        Object counted = request.getAttribute(RequestSizeLimitFilter.BODY_BYTES);
        long bytes = counted instanceof Long n && n > 0 ? n
                : request.getContentLengthLong() >= 0 ? request.getContentLengthLong()
                : input.toString().getBytes(StandardCharsets.UTF_8).length;
        return new RunService.Inbound(headers, bytes);
    }

    static boolean prefersAsync(String prefer) {
        if (prefer == null) {
            return false;
        }
        for (String token : prefer.split(",")) {
            if (token.trim().toLowerCase(java.util.Locale.ROOT).startsWith(RESPOND_ASYNC)) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<RunResource> respond(UUID runId, URI location, long waitStart, boolean replayed) {
        RunResource resource = assembler.assemble(runId);
        boolean done = RunStatus.valueOf(resource.status()).doneForCaller();
        ResponseEntity.BodyBuilder builder = done ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.ACCEPTED).location(location)
                        .header("Retry-After", "2");
        log.info("run responded runId={} httpStatus={} waitedMs={}", runId, done ? 200 : 202,
                (System.nanoTime() - waitStart) / 1_000_000);
        if (replayed) {
            builder.header(REPLAYED, "true");
        }
        return builder.header("X-Apiorch-Run-Id", runId.toString())
                .header("X-Apiorch-Run-Status", resource.status()).body(resource);
    }
}
