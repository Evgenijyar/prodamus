package ru.prodamus.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "client_refresh_token")
public class ClientRefreshToken {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "device_id", nullable = false, length = 180)
    private String deviceId;
    @Column(name = "device_name", length = 180)
    private String deviceName;
    @Column(nullable = false)
    private boolean persistent;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_used_at")
    private Instant lastUsedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist void prePersist() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public boolean isPersistent() { return persistent; }
    public void setPersistent(boolean persistent) { this.persistent = persistent; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
