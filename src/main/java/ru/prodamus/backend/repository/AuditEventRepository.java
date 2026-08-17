package ru.prodamus.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.prodamus.backend.model.AuditEvent;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findTop100ByOrderByCreatedAtDesc();
}
