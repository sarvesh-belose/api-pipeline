package com.crisil.apiorch.run;

import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import com.crisil.apiorch.config.ExecutorConfig;
import com.crisil.apiorch.engine.RunStatus;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.CorrelationIdFilter;
import com.crisil.apiorch.observability.ProblemTypes;
import com.crisil.apiorch.run.dto.RunResource;

/**
 * {@code POST /api/v1/pipelines/{code}/runs} (US-4.1 sync, US-4.2 async) and {@code GET /api/v1/runs/{runId}}. Sync
 * requests return a {@code CompletableFuture}: the Tomcat thread is released while the run executes (AC8).
 */
@RestController
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    private final RunService runs;
    private final RunResourceAssembler assembler;
    private final ThreadPoolTaskExecutor responses;

    public RunController(RunService runs, RunResourceAssembler assembler,
            @Qualifier(ExecutorConfig.RUN_RESPONSE) ThreadPoolTaskExecutor responses) {
        this.runs = runs;
        this.assembler = assembler;
        this.responses = responses;
    }

    @PostMapping("/api/v1/pipelines/{code}/runs")
    public CompletableFuture<ResponseEntity<RunResource>> start(@PathVariable String code,
            @RequestParam(name = "mode", defaultValue = "sync") String mode,
            @RequestParam(name = "version", required = false) Integer version,
            @RequestBody JsonNode input, HttpServletRequest request, Authentication caller) {
        boolean sync = switch (mode) {
            case "sync" -> true;
            case "async" -> false;
            default -> throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "mode must be sync or async");
        };
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        RunService.Started started = runs.start(code, version, input, sync, correlationId, caller);
        long waitStart = System.nanoTime();
        return started.done().thenApplyAsync(done -> respond(started.runId(), waitStart), responses);
    }

    @GetMapping("/api/v1/runs/{runId}")
    public ResponseEntity<RunResource> get(@PathVariable UUID runId) {
        RunResource resource = assembler.assemble(runId);
        return ResponseEntity.ok().header("X-Apiorch-Run-Id", runId.toString())
                .header("X-Apiorch-Run-Status", resource.status()).body(resource);
    }

    private ResponseEntity<RunResource> respond(UUID runId, long waitStart) {
        RunResource resource = assembler.assemble(runId);
        boolean done = RunStatus.valueOf(resource.status()).doneForCaller();
        ResponseEntity.BodyBuilder builder = done ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/runs/" + runId))
                        .header("Retry-After", "2");
        log.info("run responded runId={} httpStatus={} waitedMs={}", runId, done ? 200 : 202,
                (System.nanoTime() - waitStart) / 1_000_000);
        return builder.header("X-Apiorch-Run-Id", runId.toString())
                .header("X-Apiorch-Run-Status", resource.status()).body(resource);
    }
}
