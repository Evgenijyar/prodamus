package ru.prodamus.backend.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;
    @Column(name = "actor", length = 180)
    private String actor;
    @Column(name = "subject", length = 250)
    private String subject;
    @Column(columnDefinition = "text")
    private String detail;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
