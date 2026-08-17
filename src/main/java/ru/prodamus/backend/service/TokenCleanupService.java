package ru.prodamus.backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.repository.ClientAccessTokenRepository;
import ru.prodamus.backend.repository.ClientRefreshTokenRepository;

import java.time.Instant;

@Service
public class TokenCleanupService {
    private final ClientAccessTokenRepository accessTokens;
    private final ClientRefreshTokenRepository refreshTokens;

    public TokenCleanupService(ClientAccessTokenRepository accessTokens, ClientRefreshTokenRepository refreshTokens) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    @Transactional
    public void removeExpiredTokens() {
        Instant now = Instant.now();
        accessTokens.deleteByExpiresAtBefore(now);
        refreshTokens.deleteByExpiresAtBefore(now);
    }
}
