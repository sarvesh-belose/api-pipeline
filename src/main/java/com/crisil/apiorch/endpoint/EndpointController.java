package com.crisil.apiorch.endpoint;

import java.net.URI;
import java.security.Principal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crisil.apiorch.endpoint.dto.EndpointRequest;
import com.crisil.apiorch.endpoint.dto.EndpointResponse;
import com.crisil.apiorch.endpoint.dto.ProbeResult;
import com.crisil.apiorch.http.EndpointProbe;
import com.crisil.apiorch.security.CurrentActor;
import com.crisil.apiorch.web.ETags;

/** {@code /api/v1/admin/endpoints} (US-2.1, README §9). Roles arrive with US-1.2. */
@RestController
@RequestMapping("/api/v1/admin/endpoints")
public class EndpointController {

    private final EndpointService service;
    private final EndpointProbe probe;
    private final CurrentActor actor;

    public EndpointController(EndpointService service, EndpointProbe probe, CurrentActor actor) {
        this.service = service;
        this.probe = probe;
        this.actor = actor;
    }

    @PostMapping
    public ResponseEntity<EndpointResponse> create(@RequestBody EndpointRequest request, Principal principal) {
        EndpointConfig created = service.create(request, actor.of(principal));
        return ResponseEntity.created(URI.create("/api/v1/admin/endpoints/" + created.name()))
                .eTag(ETags.of(created.version()))
                .body(EndpointResponse.of(created));
    }

    @GetMapping
    public List<EndpointResponse> list() {
        return service.list().stream().map(EndpointResponse::of).toList();
    }

    @GetMapping("/{name}")
    public ResponseEntity<EndpointResponse> get(@PathVariable String name) {
        EndpointConfig config = service.get(name);
        return ResponseEntity.ok().eTag(ETags.of(config.version())).body(EndpointResponse.of(config));
    }

    @PutMapping("/{name}")
    public ResponseEntity<EndpointResponse> update(@PathVariable String name, @RequestBody EndpointRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch, Principal principal) {
        EndpointConfig updated = service.update(name, request, ETags.parse(ifMatch), actor.of(principal));
        return ResponseEntity.ok().eTag(ETags.of(updated.version())).body(EndpointResponse.of(updated));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name, Principal principal) {
        service.delete(name, actor.of(principal));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/probe")
    public ProbeResult probe(@PathVariable String name) {
        return probe.probe(name);
    }
}
