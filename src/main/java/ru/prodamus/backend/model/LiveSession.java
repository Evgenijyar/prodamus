package ru.prodamus.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "live_session")
public class LiveSession {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prompt_profile_id", nullable = false)
    private PromptProfile promptProfile;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ai_credential_id", nullable = false)
    private AiCredential aiCredential;
    @Column(nullable = false, length = 30)
    private String status;
    @Column(name = "device_id", nullable = false, length = 180)
    private String deviceId;
    @Column(name = "client_version", length = 60)
    private String clientVersion;
    @Column(name = "prompt_version", nullable = false)
    private int promptVersion;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "activated_at")
    private Instant activatedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;
    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;
    @Column(name = "close_reason", length = 500)
    private String closeReason;

    @PrePersist void prePersist() { if (id == null) id = UUID.randomUUID(); if (startedAt == null) startedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public PromptProfile getPromptProfile() { return promptProfile; }
    public void setPromptProfile(PromptProfile promptProfile) { this.promptProfile = promptProfile; }
    public AiCredential getAiCredential() { return aiCredential; }
    public void setAiCredential(AiCredential aiCredential) { this.aiCredential = aiCredential; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getClientVersion() { return clientVersion; }
    public void setClientVersion(String clientVersion) { this.clientVersion = clientVersion; }
    public int getPromptVersion() { return promptVersion; }
    public void setPromptVersion(int promptVersion) { this.promptVersion = promptVersion; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Instant getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(Instant tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
}
