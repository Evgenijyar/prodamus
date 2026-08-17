package ru.prodamus.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.AppUser;
import ru.prodamus.backend.model.ClientAccessToken;
import ru.prodamus.backend.model.ClientRefreshToken;
import ru.prodamus.backend.repository.AppUserRepository;
import ru.prodamus.backend.repository.ClientAccessTokenRepository;
import ru.prodamus.backend.repository.ClientRefreshTokenRepository;
import ru.prodamus.backend.security.PasswordHasher;
import ru.prodamus.backend.security.TokenService;

import java.time.Duration;
import java.time.Instant;

@Service
public class ClientAuthService {
    private final AppUserRepository users;
    private final ClientAccessTokenRepository accessRepository;
    private final ClientRefreshTokenRepository refreshRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final Duration accessTtl;
    private final Duration normalRefreshTtl;
    private final Duration rememberRefreshTtl;

    public ClientAuthService(AppUserRepository users,
                             ClientAccessTokenRepository accessRepository,
                             ClientRefreshTokenRepository refreshRepository,
                             PasswordHasher passwordHasher,
                             TokenService tokenService,
                             @Value("${prodamus.auth.access-token-minutes:30}") long accessMinutes,
                             @Value("${prodamus.auth.refresh-token-hours:24}") long refreshHours,
                             @Value("${prodamus.auth.remember-refresh-token-days:7}") long rememberDays) {
        this.users = users;
        this.accessRepository = accessRepository;
        this.refreshRepository = refreshRepository;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.accessTtl = Duration.ofMinutes(Math.max(5, accessMinutes));
        this.normalRefreshTtl = Duration.ofHours(Math.max(1, refreshHours));
        this.rememberRefreshTtl = Duration.ofDays(Math.max(1, rememberDays));
    }

    @Transactional
    public TokenPair login(String login, String password, String deviceId, String deviceName, boolean rememberMe) {
        AppUser user = users.findByLoginIgnoreCase(login == null ? "" : login.trim())
                .orElseThrow(() -> ApiException.unauthorized("Неверный логин или пароль."));
        if (!user.isEnabled() || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw ApiException.unauthorized("Неверный логин или пароль.");
        }
        String normalizedDevice = requiredDevice(deviceId);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        return issue(user, normalizedDevice, deviceName, rememberMe, null);
    }

    @Transactional
    public TokenPair refresh(String refreshToken, String deviceId, String deviceName) {
        Instant now = Instant.now();
        ClientRefreshToken stored = refreshRepository.findByTokenHash(tokenService.sha256(requireToken(refreshToken)))
                .orElseThrow(() -> ApiException.unauthorized("Сессия завершена. Войдите снова."));
        if (stored.getRevokedAt() != null || !stored.getExpiresAt().isAfter(now)) {
            throw ApiException.unauthorized("Сессия истекла. Войдите снова.");
        }
        String normalizedDevice = requiredDevice(deviceId);
        if (!stored.getDeviceId().equals(normalizedDevice)) {
            throw ApiException.unauthorized("Сессия принадлежит другому устройству.");
        }
        AppUser user = stored.getUser();
        if (!user.isEnabled()) throw ApiException.forbidden("Доступ пользователя отключён.");

        boolean persistent = stored.isPersistent();
        Instant originalExpiry = stored.getExpiresAt();
        stored.setRevokedAt(now);
        stored.setLastUsedAt(now);
        refreshRepository.save(stored);
        return issue(user, normalizedDevice, deviceName, persistent, originalExpiry);
    }

    @Transactional(readOnly = true)
    public AuthenticatedClient authenticate(String bearerToken) {
        Instant now = Instant.now();
        ClientAccessToken stored = accessRepository.findByTokenHash(tokenService.sha256(requireToken(bearerToken)))
                .orElseThrow(() -> ApiException.unauthorized("Недействительный access token."));
        if (stored.getRevokedAt() != null || !stored.getExpiresAt().isAfter(now)) {
            throw ApiException.unauthorized("Access token истёк.");
        }
        if (!stored.getUser().isEnabled()) throw ApiException.forbidden("Доступ пользователя отключён.");
        return new AuthenticatedClient(stored.getUser().getId(), stored.getDeviceId());
    }

    @Transactional
    public void logout(String accessToken, String refreshToken) {
        Instant now = Instant.now();
        if (accessToken != null && !accessToken.isBlank()) {
            accessRepository.findByTokenHash(tokenService.sha256(accessToken)).ifPresent(token -> { token.setRevokedAt(now); accessRepository.save(token); });
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshRepository.findByTokenHash(tokenService.sha256(refreshToken)).ifPresent(token -> { token.setRevokedAt(now); refreshRepository.save(token); });
        }
    }

    private TokenPair issue(AppUser user, String deviceId, String deviceName, boolean persistent, Instant inheritedRefreshExpiry) {
        Instant now = Instant.now();
        String accessRaw = tokenService.randomToken();
        ClientAccessToken access = new ClientAccessToken();
        access.setUser(user);
        access.setTokenHash(tokenService.sha256(accessRaw));
        access.setDeviceId(deviceId);
        access.setExpiresAt(now.plus(accessTtl));
        accessRepository.save(access);

        String refreshRaw = tokenService.randomToken();
        ClientRefreshToken refresh = new ClientRefreshToken();
        refresh.setUser(user);
        refresh.setTokenHash(tokenService.sha256(refreshRaw));
        refresh.setDeviceId(deviceId);
        refresh.setDeviceName(deviceName == null || deviceName.isBlank() ? null : trim(deviceName, 180));
        refresh.setPersistent(persistent);
        Instant refreshExpiry = inheritedRefreshExpiry != null
                ? inheritedRefreshExpiry
                : now.plus(persistent ? rememberRefreshTtl : normalRefreshTtl);
        if (!refreshExpiry.isAfter(now)) throw ApiException.unauthorized("Сессия истекла. Войдите снова.");
        refresh.setExpiresAt(refreshExpiry);
        refreshRepository.save(refresh);

        return new TokenPair(accessRaw, access.getExpiresAt(), refreshRaw, refreshExpiry, persistent, user);
    }

    private String requireToken(String token) { if (token == null || token.isBlank()) throw ApiException.unauthorized("Токен не передан."); return token.trim(); }
    private String requiredDevice(String deviceId) { if (deviceId == null || deviceId.isBlank()) throw ApiException.badRequest("DEVICE_ID_REQUIRED", "deviceId обязателен."); return trim(deviceId.trim(), 180); }
    private String trim(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    public record TokenPair(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt,
                            boolean persistent, AppUser user) {}
    public record AuthenticatedClient(Long userId, String deviceId) {}
}
