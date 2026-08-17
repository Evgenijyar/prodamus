package ru.prodamus.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.model.AuditEvent;
import ru.prodamus.backend.repository.AuditEventRepository;

@Service
public class AuditService {
    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String type, String actor, String subject, String detail) {
        AuditEvent event = new AuditEvent();
        event.setEventType(trim(type, 100));
        event.setActor(trim(actor, 180));
        event.setSubject(trim(subject, 250));
        event.setDetail(detail);
        repository.save(event);
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
