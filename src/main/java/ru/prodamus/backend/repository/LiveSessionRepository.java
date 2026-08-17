package ru.prodamus.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.prodamus.backend.model.LiveSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveSessionRepository extends JpaRepository<LiveSession, UUID> {
    Optional<LiveSession> findByIdAndUser_Id(UUID id, Long userId);
    List<LiveSession> findTop200ByOrderByStartedAtDesc();
    List<LiveSession> findTop30ByUser_IdOrderByStartedAtDesc(Long userId);
    List<LiveSession> findByUser_IdAndStatusIn(Long userId, List<String> statuses);
    List<LiveSession> findByStatusInAndLeaseExpiresAtBefore(List<String> statuses, Instant now);

    @Query("select count(s) from LiveSession s where s.aiCredential.id = :credentialId " +
           "and s.status in ('PROVISIONING','ACTIVE') and s.leaseExpiresAt > :now")
    long countLeasedForCredential(@Param("credentialId") Long credentialId, @Param("now") Instant now);

    @Query("select count(s) from LiveSession s where s.user.id = :userId " +
           "and s.status in ('PROVISIONING','ACTIVE') and s.leaseExpiresAt > :now")
    long countActiveForUser(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("select count(s) from LiveSession s where s.status in ('PROVISIONING','ACTIVE') and s.leaseExpiresAt > :now")
    long countActive(@Param("now") Instant now);
}
