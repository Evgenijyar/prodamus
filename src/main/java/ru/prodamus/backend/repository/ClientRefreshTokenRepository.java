package ru.prodamus.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.prodamus.backend.model.ClientRefreshToken;

import java.time.Instant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientRefreshTokenRepository extends JpaRepository<ClientRefreshToken, UUID> {
    Optional<ClientRefreshToken> findByTokenHash(String tokenHash);
    long deleteByUser_Id(Long userId);
    long deleteByDeviceId(String deviceId);
    long deleteByUser_IdAndDeviceId(Long userId, String deviceId);
    List<ClientRefreshToken> findByUser_IdAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);
    long deleteByExpiresAtBefore(Instant cutoff);
}
