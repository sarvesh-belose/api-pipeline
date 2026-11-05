package testsupport.web;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Test-only endpoints on the real API paths, so the error, size-limit and binding behaviour of US-1.1 can be proven
 * before the stories that own those paths exist. Outside the {@code com.crisil.apiorch} scan; tests {@code @Import}
 * it.
 */
@RestController
public class TestEndpoints {

    public record TypedBody(@NotBlank String name, @Size(max = 5) String code) {
    }

    @PostMapping("/api/v1/admin/test/typed")
    public Map<String, Object> typed(@Valid @RequestBody TypedBody body) {
        return Map.of("name", body.name());
    }

    @PostMapping("/api/v1/admin/test/free")
    public Map<String, Object> adminFree(@RequestBody JsonNode body) {
        return Map.of("bytes", body.toString().length());
    }

    @PostMapping("/api/v1/pipelines/{code}/runs")
    public Map<String, Object> runs(@PathVariable String code, @RequestBody JsonNode body) {
        return Map.of("code", code, "bytes", body.toString().length());
    }

    @PostMapping("/api/v1/callbacks/{runId}/{stepId}")
    public Map<String, Object> callbacks(@PathVariable String runId, @PathVariable String stepId,
            @RequestBody JsonNode body) {
        return Map.of("bytes", body.toString().length());
    }

    @GetMapping("/api/v1/test/boom")
    public Map<String, Object> boom() {
        throw new IllegalStateException("secret internal detail com.crisil.apiorch.Internal");
    }

    @GetMapping("/api/v1/test/async-fail")
    public java.util.concurrent.CompletableFuture<Map<String, Object>> asyncFail() {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            throw new com.crisil.apiorch.observability.ApiProblemException(
                    org.springframework.http.HttpStatus.CONFLICT, "illegal-run-state", "failed on another thread");
        });
    }

    @PostMapping("/api/v1/test/accepted")
    public org.springframework.http.ResponseEntity<Void> accepted() {
        return org.springframework.http.ResponseEntity.accepted().location(
                org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/runs/{id}").buildAndExpand("r-1").toUri()).build();
    }

    @GetMapping("/api/v1/test/slow")
    public Map<String, Object> slow() throws InterruptedException {
        Thread.sleep(1500);
        return Map.of("done", true);
    }
}
