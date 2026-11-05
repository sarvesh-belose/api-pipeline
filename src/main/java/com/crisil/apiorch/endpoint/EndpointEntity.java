package com.crisil.apiorch.endpoint;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** The {@code endpoint} row (README §10, {@code V2__endpoint.sql}). {@code row_version} is the JPA version. */
@Entity
@Table(name = "endpoint")
public class EndpointEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 63)
    private String name;

    private String description;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "health_path")
    private String healthPath;

    @Column(name = "connect_timeout_ms", nullable = false)
    private int connectTimeoutMs;

    @Column(name = "read_timeout_ms", nullable = false)
    private int readTimeoutMs;

    @Column(name = "http_version", nullable = false)
    private String httpVersion;

    @Column(name = "auth_type", nullable = false)
    private String authType;

    @Column(name = "auth_config")
    private String authConfig;

    @Column(name = "resilience_config")
    private String resilienceConfig;

    @Column(nullable = false)
    private boolean enabled;

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

    protected EndpointEntity() {
    }

    public EndpointEntity(UUID id, String name, Instant createdAt, String createdBy) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = createdAt;
        this.updatedBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getHealthPath() { return healthPath; }
    public void setHealthPath(String healthPath) { this.healthPath = healthPath; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public String getHttpVersion() { return httpVersion; }
    public void setHttpVersion(String httpVersion) { this.httpVersion = httpVersion; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getAuthConfig() { return authConfig; }
    public void setAuthConfig(String authConfig) { this.authConfig = authConfig; }
    public String getResilienceConfig() { return resilienceConfig; }
    public void setResilienceConfig(String resilienceConfig) { this.resilienceConfig = resilienceConfig; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getRowVersion() { return rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }

    public void touch(Instant at, String by) {
        this.updatedAt = at;
        this.updatedBy = by;
    }
}
