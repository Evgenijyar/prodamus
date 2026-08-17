package ru.prodamus.backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.prodamus.backend.model.AiCredential;

import java.util.List;

public interface AiCredentialRepository extends JpaRepository<AiCredential, Long> {
    boolean existsByNameIgnoreCase(String name);
    List<AiCredential> findAllByOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AiCredential c where c.enabled = true and c.healthStatus <> 'ERROR' order by c.id")
    List<AiCredential> lockEnabledCredentials();
}
