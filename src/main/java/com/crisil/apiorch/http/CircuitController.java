package com.crisil.apiorch.http;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.crisil.apiorch.config.InstanceIdentity;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.security.CurrentActor;

/**
 * This node's circuit for one endpoint (US-5.3 AC6), for {@code apiorch.ops}: its state and counters, and a reset
 * with a reason. {@code allNodes=true} also bumps the endpoint's {@code row_version}, so the US-2.1 refresh rebuilds
 * the runtime, with a closed circuit, on every node within {@code apiorch.endpoints.refresh-interval-ms}.
 */
@RestController
public class CircuitController {

    private static final Logger log = LoggerFactory.getLogger(CircuitController.class);

    public record ResetRequest(@NotBlank @Size(max = 500) String reason) {
    }

    private final EndpointClientRegistry endpoints;
    private final JdbcClient jdbc;
    private final CurrentActor actors;
    private final String nodeId;

    public CircuitController(EndpointClientRegistry endpoints, JdbcClient jdbc, CurrentActor actors,
            InstanceIdentity instance) {
        this.endpoints = endpoints;
        this.jdbc = jdbc;
        this.actors = actors;
        this.nodeId = instance.id();
    }

    @GetMapping("/api/v1/admin/endpoints/{name}/circuit")
    public Map<String, Object> state(@PathVariable String name) {
        return view(runtime(name));
    }

    @PostMapping("/api/v1/admin/endpoints/{name}/circuit/reset")
    public Map<String, Object> reset(@PathVariable String name,
            @RequestParam(name = "allNodes", defaultValue = "false") boolean allNodes,
            @Valid @RequestBody ResetRequest request, Principal principal) {
        EndpointRuntime runtime = runtime(name);
        runtime.circuitBreaker().reset();
        if (allNodes) {
            jdbc.sql("UPDATE endpoint SET row_version = row_version + 1, updated_at = SYSUTCDATETIME() WHERE name = :n")
                    .param("n", name).update();
        }
        log.warn("circuit reset endpoint={} allNodes={} actor={} reason={}", name, allNodes, actors.of(principal),
                request.reason());
        return view(runtime);
    }

    private EndpointRuntime runtime(String name) {
        return endpoints.get(name).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "endpoint-not-found", "Endpoint '" + name + "' does not exist."));
    }

    private Map<String, Object> view(EndpointRuntime runtime) {
        CircuitBreaker cb = runtime.circuitBreaker();
        CircuitBreaker.Metrics m = cb.getMetrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", runtime.name());
        out.put("nodeId", nodeId);
        out.put("state", cb.getState().name());
        out.put("failureRate", m.getFailureRate());
        out.put("slowCallRate", m.getSlowCallRate());
        out.put("bufferedCalls", m.getNumberOfBufferedCalls());
        out.put("failedCalls", m.getNumberOfFailedCalls());
        out.put("slowCalls", m.getNumberOfSlowCalls());
        out.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
        Instant since = endpoints.circuitStateSince(runtime.name());
        if (since != null) {
            out.put("stateSince", since);
        }
        return out;
    }
}
