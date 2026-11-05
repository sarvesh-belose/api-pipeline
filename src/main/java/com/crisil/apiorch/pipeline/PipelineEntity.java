package com.crisil.apiorch.pipeline;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** The {@code pipeline} row: one per code, with its on/off switch (US-3.1, US-3.7; {@code V3__pipeline.sql}). */
@Entity
@Table(name = "pipeline")
public class PipelineEntity {

    @Id
    @Column(length = 64)
    private String code;

    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "disabled_reason")
    private String disabledReason;

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

    protected PipelineEntity() {
    }

    public PipelineEntity(String code, String description, Instant at, String by) {
        this.code = code;
        this.description = description;
        this.enabled = true;
        this.createdAt = at;
        this.createdBy = by;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public long getRowVersion() { return rowVersion; }

    public void describe(String description, Instant at, String by) {
        this.description = description;
        this.updatedAt = at;
        this.updatedBy = by;
    }
}
