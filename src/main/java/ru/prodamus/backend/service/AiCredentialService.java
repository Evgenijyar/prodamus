package ru.prodamus.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.AiCredential;
import ru.prodamus.backend.repository.AiCredentialRepository;
import ru.prodamus.backend.security.SecretCryptoService;

import java.time.Instant;
import java.util.List;

@Service
public class AiCredentialService {
    private final AiCredentialRepository repository;
    private final SecretCryptoService crypto;
    private final GeminiTokenService gemini;
    private final SystemConfigService systemConfig;

    public AiCredentialService(AiCredentialRepository repository, SecretCryptoService crypto,
                               GeminiTokenService gemini, SystemConfigService systemConfig) {
        this.repository = repository;
        this.crypto = crypto;
        this.gemini = gemini;
        this.systemConfig = systemConfig;
    }

    @Transactional(readOnly = true)
    public List<AiCredential> list() { return repository.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public AiCredential require(Long id) { return repository.findById(id).orElseThrow(() -> ApiException.notFound("AI-ключ не найден.")); }

    @Transactional
    public AiCredential create(String name, String apiKey, boolean enabled, int maxConcurrentSessions) {
        String normalizedName = required(name, "Укажите название ключа.");
        if (repository.existsByNameIgnoreCase(normalizedName)) throw ApiException.conflict("CREDENTIAL_NAME_EXISTS", "AI-ключ с таким названием уже существует.");
        String normalizedKey = required(apiKey, "API key обязателен.");
        AiCredential credential = new AiCredential();
        credential.setName(normalizedName);
        credential.setProvider("GEMINI");
        credential.setEncryptedApiKey(crypto.encrypt(normalizedKey));
        credential.setKeyHint(mask(normalizedKey));
        credential.setEnabled(enabled);
        credential.setMaxConcurrentSessions(capacity(maxConcurrentSessions));
        credential.setHealthStatus("UNKNOWN");
        return repository.save(credential);
    }

    @Transactional
    public AiCredential update(Long id, String name, String apiKey, boolean enabled, int maxConcurrentSessions) {
        AiCredential credential = require(id);
        String normalizedName = required(name, "Укажите название ключа.");
        repository.findAllByOrderByNameAsc().stream()
                .filter(other -> !other.getId().equals(id) && other.getName().equalsIgnoreCase(normalizedName))
                .findAny().ifPresent(other -> { throw ApiException.conflict("CREDENTIAL_NAME_EXISTS", "AI-ключ с таким названием уже существует."); });
        credential.setName(normalizedName);
        if (apiKey != null && !apiKey.isBlank()) {
            String normalizedKey = apiKey.trim();
            credential.setEncryptedApiKey(crypto.encrypt(normalizedKey));
            credential.setKeyHint(mask(normalizedKey));
            credential.setHealthStatus("UNKNOWN");
            credential.setLastError(null);
        }
        credential.setEnabled(enabled);
        credential.setMaxConcurrentSessions(capacity(maxConcurrentSessions));
        return repository.save(credential);
    }

    @Transactional
    public AiCredential disable(Long id) {
        AiCredential credential = require(id);
        credential.setEnabled(false);
        return repository.save(credential);
    }

    public TestResult test(Long id) {
        AiCredential credential = require(id);
        try {
            String model = systemConfig.get().getDefaultModel();
            GeminiTokenService.TokenResult token = gemini.createConstrainedToken(
                    crypto.decrypt(credential.getEncryptedApiKey()), model,
                    "Проверка подключения Prodamus. Не начинай диалог до получения аудиовхода.");
            updateHealth(id, "OK", null);
            return new TestResult(true, "Ключ принят Gemini. Ephemeral token успешно создан.", token.expiresAt());
        } catch (RuntimeException ex) {
            updateHealth(id, "ERROR", ex.getMessage());
            throw ex;
        }
    }

    @Transactional
    protected void updateHealth(Long id, String status, String error) {
        AiCredential credential = require(id);
        credential.setHealthStatus(status);
        credential.setLastError(error == null ? null : (error.length() > 3000 ? error.substring(0, 3000) : error));
        credential.setLastCheckedAt(Instant.now());
        repository.save(credential);
    }

    public String decrypt(AiCredential credential) { return crypto.decrypt(credential.getEncryptedApiKey()); }

    private int capacity(int value) { return Math.max(1, Math.min(100, value)); }
    private String required(String value, String message) { if (value == null || value.isBlank()) throw ApiException.badRequest("VALIDATION_ERROR", message); return value.trim(); }
    private String mask(String key) { if (key.length() <= 8) return "••••" + key.substring(Math.max(0, key.length()-2)); return key.substring(0, 4) + "••••••" + key.substring(key.length()-4); }
    public record TestResult(boolean ok, String message, Instant tokenExpiresAt) {}
}
