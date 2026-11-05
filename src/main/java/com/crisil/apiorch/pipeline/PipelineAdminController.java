package com.crisil.apiorch.pipeline;

import java.net.URI;
import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crisil.apiorch.pipeline.dto.DraftRequest;
import com.crisil.apiorch.pipeline.dto.DraftResponse;
import com.crisil.apiorch.pipeline.dto.PipelineSummary;
import com.crisil.apiorch.pipeline.dto.PublishRequest;
import com.crisil.apiorch.pipeline.dto.ValidateResponse;
import com.crisil.apiorch.pipeline.dto.VersionResponse;
import com.crisil.apiorch.pipeline.dto.VersionSummary;
import com.crisil.apiorch.security.CurrentActor;
import com.crisil.apiorch.web.ETags;

/** {@code /api/v1/admin/pipelines}: drafts (US-3.1), validate (US-3.2), publish and versions (US-3.3). */
@RestController
@RequestMapping("/api/v1/admin/pipelines")
public class PipelineAdminController {

    private final DraftService drafts;
    private final ValidateService validation;
    private final PublishService publishing;
    private final CurrentActor actor;

    public PipelineAdminController(DraftService drafts, ValidateService validation, PublishService publishing,
            CurrentActor actor) {
        this.drafts = drafts;
        this.validation = validation;
        this.publishing = publishing;
        this.actor = actor;
    }

    @PostMapping("/{code}/publish")
    public ResponseEntity<VersionResponse> publish(@PathVariable String code,
            @RequestBody(required = false) @Validated PublishRequest request, Principal principal) {
        VersionResponse published = publishing.publish(code, request == null ? null : request.comment(),
                actor.of(principal));
        return ResponseEntity.created(URI.create("/api/v1/admin/pipelines/" + code + "/versions/"
                + published.version())).body(published);
    }

    @GetMapping("/{code}/versions")
    public List<VersionSummary> versions(@PathVariable String code) {
        return publishing.list(code);
    }

    @GetMapping("/{code}/versions/{version}")
    public VersionResponse version(@PathVariable String code, @PathVariable String version) {
        return publishing.get(code, version);
    }

    /** US-3.2: validate the draft, or the definition in the body, without saving. */
    @PostMapping("/{code}/validate")
    public ValidateResponse validate(@PathVariable String code,
            @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body,
            @RequestParam(name = "detail", defaultValue = "false") boolean detail) {
        return validation.validate(code, body, detail);
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
