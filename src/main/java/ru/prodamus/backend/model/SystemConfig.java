package ru.prodamus.backend.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "system_config")
public class SystemConfig {
    @Id private Long id;
    @Column(name = "global_prompt", nullable = false, columnDefinition = "text")
    private String globalPrompt = "";
    @Column(name = "minimum_client_version", nullable = false, length = 40)
    private String minimumClientVersion = "0.1.0";
    @Column(name = "latest_client_version", nullable = false, length = 40)
    private String latestClientVersion = "0.1.0";
    @Column(name = "default_model", nullable = false, length = 160)
    private String defaultModel;
    @Column(name = "client_download_url", nullable = false, length = 500)
    private String clientDownloadUrl = "";
    @Column(name = "feature_expanded_mode", nullable = false)
    private boolean featureExpandedMode = true;
    @Column(name = "feature_manual_client_context", nullable = false)
    private boolean featureManualClientContext = false;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGlobalPrompt() { return globalPrompt; }
    public void setGlobalPrompt(String globalPrompt) { this.globalPrompt = globalPrompt == null ? "" : globalPrompt; }
    public String getMinimumClientVersion() { return minimumClientVersion; }
    public void setMinimumClientVersion(String minimumClientVersion) { this.minimumClientVersion = minimumClientVersion; }
    public String getLatestClientVersion() { return latestClientVersion; }
    public void setLatestClientVersion(String latestClientVersion) { this.latestClientVersion = latestClientVersion; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getClientDownloadUrl() { return clientDownloadUrl; }
    public void setClientDownloadUrl(String clientDownloadUrl) { this.clientDownloadUrl = clientDownloadUrl == null ? "" : clientDownloadUrl.trim(); }
    public boolean isFeatureExpandedMode() { return featureExpandedMode; }
    public void setFeatureExpandedMode(boolean featureExpandedMode) { this.featureExpandedMode = featureExpandedMode; }
    public boolean isFeatureManualClientContext() { return featureManualClientContext; }
    public void setFeatureManualClientContext(boolean featureManualClientContext) { this.featureManualClientContext = featureManualClientContext; }
    public Instant getUpdatedAt() { return updatedAt; }
}
