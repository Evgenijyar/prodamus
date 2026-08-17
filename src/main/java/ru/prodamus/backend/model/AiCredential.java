package ru.prodamus.backend.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ai_credential")
public class AiCredential {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 120)
    private String name;
    @Column(nullable = false, length = 40)
    private String provider = "GEMINI";
    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "text")
    private String encryptedApiKey;
    @Column(name = "key_hint", nullable = false, length = 80)
    private String keyHint;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "max_concurrent_sessions", nullable = false)
    private int maxConcurrentSessions = 1;
    @Column(name = "health_status", nullable = false, length = 40)
    private String healthStatus = "UNKNOWN";
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void prePersist() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEncryptedApiKey() { return encryptedApiKey; }
    public void setEncryptedApiKey(String encryptedApiKey) { this.encryptedApiKey = encryptedApiKey; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxConcurrentSessions() { return maxConcurrentSessions; }
    public void setMaxConcurrentSessions(int maxConcurrentSessions) { this.maxConcurrentSessions = maxConcurrentSessions; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
