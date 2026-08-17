package ru.prodamus.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.AppUser;
import ru.prodamus.backend.model.PromptProfile;
import ru.prodamus.backend.repository.AppUserRepository;
import ru.prodamus.backend.repository.ClientAccessTokenRepository;
import ru.prodamus.backend.repository.ClientRefreshTokenRepository;
import ru.prodamus.backend.repository.PromptProfileRepository;
import ru.prodamus.backend.security.PasswordHasher;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class UserService {
    private final AppUserRepository users;
    private final PromptProfileRepository prompts;
    private final ClientAccessTokenRepository accessTokens;
    private final ClientRefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;

    public UserService(AppUserRepository users, PromptProfileRepository prompts,
                       ClientAccessTokenRepository accessTokens, ClientRefreshTokenRepository refreshTokens,
                       PasswordHasher passwordHasher) {
        this.users = users;
        this.prompts = prompts;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
    }

    @Transactional(readOnly = true)
    public List<AppUser> list() { return users.findAllByOrderByDisplayNameAsc(); }

    @Transactional(readOnly = true)
    public AppUser require(Long id) { return users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден.")); }

    @Transactional
    public AppUser create(String login, String displayName, String email, String password, boolean enabled,
                          String customInstructions, Set<Long> promptIds) {
        String normalizedLogin = normalizeLogin(login);
        if (users.existsByLoginIgnoreCase(normalizedLogin)) {
            throw ApiException.conflict("LOGIN_EXISTS", "Пользователь с таким логином уже существует.");
        }
        if (password == null || password.length() < 6) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Пароль пользователя должен содержать минимум 6 символов.");
        }
        AppUser user = new AppUser();
        user.setLogin(normalizedLogin);
        user.setDisplayName(required(displayName, "Укажите имя пользователя."));
        user.setEmail(blankToNull(email));
        user.setPasswordHash(passwordHasher.hash(password));
        user.setEnabled(enabled);
        user.setCustomInstructions(customInstructions);
        user.setPromptProfiles(loadPrompts(promptIds));
        return users.save(user);
    }

    @Transactional
    public AppUser update(Long id, String login, String displayName, String email, String newPassword, boolean enabled,
                          String customInstructions, Set<Long> promptIds) {
        AppUser user = require(id);
        String normalizedLogin = normalizeLogin(login);
        users.findByLoginIgnoreCase(normalizedLogin).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw ApiException.conflict("LOGIN_EXISTS", "Пользователь с таким логином уже существует.");
        });
        boolean revoke = user.isEnabled() && !enabled;
        user.setLogin(normalizedLogin);
        user.setDisplayName(required(displayName, "Укажите имя пользователя."));
        user.setEmail(blankToNull(email));
        user.setEnabled(enabled);
        user.setCustomInstructions(customInstructions);
        user.setPromptProfiles(loadPrompts(promptIds));
        if (newPassword != null && !newPassword.isBlank()) {
            if (newPassword.length() < 6) throw ApiException.badRequest("WEAK_PASSWORD", "Пароль должен содержать минимум 6 символов.");
            user.setPasswordHash(passwordHasher.hash(newPassword));
            revoke = true;
        }
        AppUser saved = users.save(user);
        if (revoke) revokeTokens(id);
        return saved;
    }

    @Transactional
    public AppUser disable(Long id) {
        AppUser user = require(id);
        user.setEnabled(false);
        revokeTokens(id);
        return users.save(user);
    }

    @Transactional
    public void revokeTokens(Long userId) {
        accessTokens.deleteByUser_Id(userId);
        refreshTokens.deleteByUser_Id(userId);
    }

    @Transactional
    public void revokeDevice(Long userId, String deviceId) {
        require(userId);
        if (deviceId == null || deviceId.isBlank()) throw ApiException.badRequest("DEVICE_ID_REQUIRED", "deviceId обязателен.");
        accessTokens.deleteByUser_IdAndDeviceId(userId, deviceId.trim());
        refreshTokens.deleteByUser_IdAndDeviceId(userId, deviceId.trim());
    }

    private Set<PromptProfile> loadPrompts(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return new LinkedHashSet<>();
        List<PromptProfile> found = prompts.findAllByIdIn(ids);
        if (found.size() != ids.size()) throw ApiException.badRequest("PROMPT_NOT_FOUND", "Одна из выбранных ролей не существует.");
        return new LinkedHashSet<>(found);
    }

    private String normalizeLogin(String value) { return required(value, "Логин обязателен.").toLowerCase(); }
    private String required(String value, String message) { if (value == null || value.isBlank()) throw ApiException.badRequest("VALIDATION_ERROR", message); return value.trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
