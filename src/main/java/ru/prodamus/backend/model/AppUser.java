package ru.prodamus.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100)
    private String login;
    @Column(name = "display_name", nullable = false, length = 180)
    private String displayName;
    @Column(length = 220)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "custom_instructions", nullable = false, columnDefinition = "text")
    private String customInstructions = "";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_prompt_profile",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "prompt_profile_id"))
    @OrderBy("sortOrder ASC, name ASC")
    private Set<PromptProfile> promptProfiles = new LinkedHashSet<>();

    @PrePersist void prePersist() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(String customInstructions) { this.customInstructions = customInstructions == null ? "" : customInstructions; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Set<PromptProfile> getPromptProfiles() { return promptProfiles; }
    public void setPromptProfiles(Set<PromptProfile> promptProfiles) { this.promptProfiles = promptProfiles == null ? new LinkedHashSet<>() : promptProfiles; }
}
