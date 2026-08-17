package ru.prodamus.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.prodamus.backend.model.ClientAccessToken;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

public interface ClientAccessTokenRepository extends JpaRepository<ClientAccessToken, UUID> {
    Optional<ClientAccessToken> findByTokenHash(String tokenHash);
    long deleteByUser_Id(Long userId);
    long deleteByDeviceId(String deviceId);
    long deleteByUser_IdAndDeviceId(Long userId, String deviceId);
    long deleteByExpiresAtBefore(Instant cutoff);
}
