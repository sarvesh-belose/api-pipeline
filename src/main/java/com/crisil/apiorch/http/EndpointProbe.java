package com.crisil.apiorch.http;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.crisil.apiorch.endpoint.dto.ProbeResult;
import com.crisil.apiorch.observability.ApiProblemException;
import com.crisil.apiorch.observability.ProblemTypes;

/**
 * {@code GET baseUrl + healthPath} with the endpoint's own timeouts and auth, bypassing circuit breaker, retry and
 * bulkhead (US-2.1 AC6). Any status counts as reachable; a connect error or timeout does not.
 */
@Component
public class EndpointProbe {

    private static final Logger log = LoggerFactory.getLogger(EndpointProbe.class);

    private final EndpointClientRegistry registry;

    public EndpointProbe(EndpointClientRegistry registry) {
        this.registry = registry;
    }

    public ProbeResult probe(String name) {
        EndpointRuntime runtime = registry.get(name).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "endpoint-not-found", "Endpoint '" + name + "' does not exist."));
        if (runtime.config().healthPath() == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED,
                    "Endpoint '" + name + "' has no healthPath to probe.");
        }
        URI uri = UriBuilder.build(runtime.config().baseUrl(), runtime.config().healthPath());
        long start = System.nanoTime();
        try {
            int status = runtime.restClient().get().uri(uri).exchange((req, res) -> res.getStatusCode().value());
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("endpoint probed name={} httpStatus={} durationMs={}", name, status, durationMs);
            return new ProbeResult(true, status, durationMs, null);
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String errorClass = rootCause(e).getClass().getSimpleName();
            log.info("endpoint probe failed name={} errorClass={} durationMs={}", name, errorClass, durationMs);
            return new ProbeResult(false, null, durationMs, errorClass);
        }
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
