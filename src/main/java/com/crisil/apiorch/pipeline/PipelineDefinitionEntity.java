package com.crisil.apiorch.pipeline;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A {@code pipeline_definition} row (US-3.1): version {@code 0} is the one editable draft, versions from {@code 1}
 * are immutable published versions (US-3.3). {@code content} is the DSL document exactly as saved.
 */
@Entity
@Table(name = "pipeline_definition")
public class PipelineDefinitionEntity {

    public static final int DRAFT_VERSION = 0;

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false, length = 64)
    private String checksum;

    private String comment;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private String publishedBy;

    protected PipelineDefinitionEntity() {
    }

    public static PipelineDefinitionEntity draft(String code, String content, String checksum, Instant at, String by) {
        PipelineDefinitionEntity e = new PipelineDefinitionEntity();
        e.id = UUID.randomUUID();
        e.code = code;
        e.version = DRAFT_VERSION;
        e.status = "DRAFT";
        e.content = content;
        e.checksum = checksum;
        e.createdAt = at;
        e.createdBy = by;
        e.updatedAt = at;
        e.updatedBy = by;
        return e;
    }

    public void replace(String content, String checksum, Instant at, String by) {
        this.content = content;
        this.checksum = checksum;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public int getVersion() { return version; }
    public String getStatus() { return status; }
    public String getContent() { return content; }
    public String getChecksum() { return checksum; }
    public long getRowVersion() { return rowVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getPublishedAt() { return publishedAt; }
}
