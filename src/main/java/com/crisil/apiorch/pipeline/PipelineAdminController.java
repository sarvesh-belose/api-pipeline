package com.crisil.apiorch.pipeline;

import java.net.URI;
import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
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

import com.crisil.apiorch.pipeline.dto.DraftRequest;
import com.crisil.apiorch.pipeline.dto.DraftResponse;
import com.crisil.apiorch.pipeline.dto.PipelineSummary;
import com.crisil.apiorch.security.CurrentActor;
import com.crisil.apiorch.web.ETags;

/** {@code /api/v1/admin/pipelines} drafts (US-3.1, README §9). Roles arrive with US-1.2. */
@RestController
@RequestMapping("/api/v1/admin/pipelines")
public class PipelineAdminController {

    private final DraftService drafts;
    private final CurrentActor actor;

    public PipelineAdminController(DraftService drafts, CurrentActor actor) {
        this.drafts = drafts;
        this.actor = actor;
    }

    @PostMapping
    public ResponseEntity<DraftResponse> create(@RequestBody DraftRequest request, Principal principal) {
        DraftResponse saved = drafts.create(request, actor.of(principal));
        return ResponseEntity.created(URI.create("/api/v1/admin/pipelines/" + saved.code() + "/draft"))
                .eTag(ETags.of(saved.rowVersion())).body(saved);
    }

    @GetMapping
    public List<PipelineSummary> list() {
        return drafts.list();
    }

    @GetMapping("/{code}/draft")
    public ResponseEntity<DraftResponse> get(@PathVariable String code) {
        DraftResponse draft = drafts.get(code);
        return ResponseEntity.ok().eTag(ETags.of(draft.rowVersion())).body(draft);
    }

    @PutMapping("/{code}/draft")
    public ResponseEntity<DraftResponse> put(@PathVariable String code, @RequestBody DraftRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch, Principal principal) {
        DraftResponse saved = drafts.put(code, request, ETags.parse(ifMatch), actor.of(principal));
        return ResponseEntity.status(saved.rowVersion() == 0 && ifMatch == null ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(ETags.of(saved.rowVersion())).body(saved);
    }

    @DeleteMapping("/{code}/draft")
    public ResponseEntity<Void> delete(@PathVariable String code, Principal principal) {
        drafts.delete(code, actor.of(principal));
        return ResponseEntity.noContent().build();
    }
}
