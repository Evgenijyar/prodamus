package ru.prodamus.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.prodamus.backend.model.SystemConfig;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {
}
