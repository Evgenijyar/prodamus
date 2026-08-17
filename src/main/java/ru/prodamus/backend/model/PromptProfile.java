package ru.prodamus.backend.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "prompt_profile")
public class PromptProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 120)
    private String name;
    @Column(length = 500)
    private String description;
    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt = "";
    @Column(name = "knowledge_base", nullable = false, columnDefinition = "text")
    private String knowledgeBase = "";
    @Column(nullable = false, length = 160)
    private String model;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(nullable = false)
    private int version = 1;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void prePersist() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt == null ? "" : systemPrompt; }
    public String getKnowledgeBase() { return knowledgeBase; }
    public void setKnowledgeBase(String knowledgeBase) { this.knowledgeBase = knowledgeBase == null ? "" : knowledgeBase; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
