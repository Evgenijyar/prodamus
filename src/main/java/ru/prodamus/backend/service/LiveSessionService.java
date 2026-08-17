package ru.prodamus.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.model.AiCredential;
import ru.prodamus.backend.model.AppUser;
import ru.prodamus.backend.model.LiveSession;
import ru.prodamus.backend.model.PromptProfile;
import ru.prodamus.backend.model.SystemConfig;
import ru.prodamus.backend.repository.AiCredentialRepository;
import ru.prodamus.backend.repository.AppUserRepository;
import ru.prodamus.backend.repository.LiveSessionRepository;
import ru.prodamus.backend.repository.PromptProfileRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LiveSessionService {
    private static final List<String> LEASED_STATUSES = List.of("PROVISIONING", "ACTIVE");
    private static final String CLIENT_DIALOG_PROTOCOL = """
            Ты — незаметный ассистент менеджера по продажам во время живого звонка.
            Ты получаешь по очереди реплики с метками [КЛИЕНТ] и [МЕНЕДЖЕР].
            Несколько последовательных фрагментов [КЛИЕНТ] могут быть частями одной длинной, ещё продолжающейся реплики. Сохраняй их общий смысл и уточняй следующую подсказку с учётом всех предыдущих частей.
            После реплики клиента дай менеджеру короткую, конкретную подсказку на русском языке:
            что ответить прямо сейчас, какой задать вопрос или как обработать возражение.
            После реплики менеджера оцени контекст, но отвечай только если есть действительно полезная следующая фраза.
            Не пересказывай диалог, не здоровайся, не называй себя, не озвучивай служебные метки.
            Пиши максимум 2–3 коротких предложения, готовых к произнесению.
            Если подсказка не нужна, ответь одним символом: —
            """;

    private final LiveSessionRepository sessions;
    private final AiCredentialRepository credentials;
    private final AppUserRepository users;
    private final PromptProfileRepository prompts;
    private final AiCredentialService credentialService;
    private final GeminiTokenService gemini;
    private final SystemConfigService systemConfigService;
    private final TransactionTemplate transactions;
    private final Duration leaseTtl;

    public LiveSessionService(LiveSessionRepository sessions, AiCredentialRepository credentials,
                              AppUserRepository users, PromptProfileRepository prompts, AiCredentialService credentialService,
                              GeminiTokenService gemini, SystemConfigService systemConfigService,
                              TransactionTemplate transactions,
                              @Value("${prodamus.live.lease-seconds:120}") long leaseSeconds) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.users = users;
        this.prompts = prompts;
        this.credentialService = credentialService;
        this.gemini = gemini;
        this.systemConfigService = systemConfigService;
        this.transactions = transactions;
        this.leaseTtl = Duration.ofSeconds(Math.max(30, leaseSeconds));
    }

    public SessionDescriptor start(Long userId, String authenticatedDeviceId, Long promptProfileId,
                                   String requestedDeviceId, String clientVersion, String manualClientContext) {
        if (requestedDeviceId != null && !requestedDeviceId.isBlank() && !authenticatedDeviceId.equals(requestedDeviceId.trim())) {
            throw ApiException.forbidden("deviceId не совпадает с авторизованным устройством.");
        }
        Reservation reservation = transactions.execute(status -> reserve(userId, authenticatedDeviceId, promptProfileId, clientVersion));
        if (reservation == null) throw ApiException.unavailable("SESSION_RESERVATION_FAILED", "Не удалось зарезервировать AI-сессию.");

        try {
            SystemConfig config = systemConfigService.get();
            String prompt = buildPrompt(config, reservation.prompt(), reservation.user(), manualClientContext);
            GeminiTokenService.TokenResult token = gemini.createConstrainedToken(
                    credentialService.decrypt(reservation.credential()), reservation.prompt().getModel(), prompt);
            transactions.executeWithoutResult(status -> activate(reservation.sessionId(), token.expiresAt()));
            return new SessionDescriptor(reservation.sessionId(), token.ephemeralToken(), token.expiresAt(),
                    token.newSessionExpiresAt(), token.websocketUrl(), token.model());
        } catch (RuntimeException ex) {
            transactions.executeWithoutResult(status -> closeInternal(reservation.sessionId(), "PROVISIONING_ERROR", trim(ex.getMessage(), 480)));
            throw ex;
        }
    }

    private Reservation reserve(Long userId, String deviceId, Long promptId, String clientVersion) {
        AppUser user = users.lockById(userId).orElseThrow(() -> ApiException.notFound("Пользователь не найден."));
        if (!user.isEnabled()) throw ApiException.forbidden("Доступ пользователя отключён.");
        PromptProfile prompt = prompts.findById(promptId).orElseThrow(() -> ApiException.notFound("Выбранная роль не найдена."));
        if (!prompt.isEnabled() || user.getPromptProfiles().stream().noneMatch(p -> p.getId().equals(promptId))) {
            throw ApiException.forbidden("Эта роль недоступна пользователю.");
        }
        Instant now = Instant.now();
        // Клиент больше не поддерживает серверную lease/heartbeat-сессию во время
        // разговора. Новый запрос ключа заменяет предыдущую запись аудита, поэтому
        // менеджер может сразу перезапустить локальный Gemini-клиент.
        for (LiveSession existing : sessions.findByUser_IdAndStatusIn(userId, LEASED_STATUSES)) {
            closeEntity(existing, "REPLACED", "Выдан новый временный ключ");
        }
        List<AiCredential> candidates = credentials.lockEnabledCredentials();
        AiCredential selected = null;
        for (AiCredential candidate : candidates) {
            long active = sessions.countLeasedForCredential(candidate.getId(), now);
            if (active < candidate.getMaxConcurrentSessions()) { selected = candidate; break; }
        }
        if (selected == null) throw ApiException.unavailable("NO_AI_CAPACITY", "Сейчас нет свободного AI-ключа. Повторите попытку через несколько секунд.");

        LiveSession session = new LiveSession();
        session.setId(UUID.randomUUID());
        session.setUser(user);
        session.setPromptProfile(prompt);
        session.setAiCredential(selected);
        session.setStatus("PROVISIONING");
        session.setDeviceId(deviceId);
        session.setClientVersion(trim(clientVersion, 60));
        session.setPromptVersion(prompt.getVersion());
        session.setStartedAt(now);
        session.setLeaseExpiresAt(now.plus(leaseTtl));
        sessions.saveAndFlush(session);
        return new Reservation(session.getId(), user, prompt, selected);
    }

    private void activate(UUID id, Instant tokenExpiresAt) {
        LiveSession session = sessions.findById(id).orElseThrow(() -> ApiException.notFound("Live-сессия не найдена."));
        session.setStatus("ACTIVE");
        session.setActivatedAt(Instant.now());
        session.setTokenExpiresAt(tokenExpiresAt);
        session.setLeaseExpiresAt(Instant.now().plus(leaseTtl));
        sessions.save(session);
    }

    public void terminateByAdmin(UUID id) {
        transactions.executeWithoutResult(status -> closeInternal(id, "TERMINATED", "Завершено администратором"));
    }

    public void terminateForUserByAdmin(Long userId) {
        transactions.executeWithoutResult(status -> {
            for (LiveSession session : sessions.findByUser_IdAndStatusIn(userId, LEASED_STATUSES)) {
                closeEntity(session, "TERMINATED", "Доступ пользователя отключён администратором");
            }
        });
    }

    @Scheduled(fixedDelayString = "${prodamus.live.cleanup-delay-ms:30000}")
    public void cleanupExpiredLeases() {
        Instant now = Instant.now();
        transactions.executeWithoutResult(status -> {
            for (LiveSession session : sessions.findByStatusInAndLeaseExpiresAtBefore(LEASED_STATUSES, now)) {
                closeEntity(session, "EXPIRED", "Lease timeout");
            }
        });
    }

    private void closeInternal(UUID id, String status, String reason) {
        sessions.findById(id).ifPresent(session -> { if (LEASED_STATUSES.contains(session.getStatus())) closeEntity(session, status, reason); });
    }

    private void closeEntity(LiveSession session, String status, String reason) {
        session.setStatus(status);
        session.setClosedAt(Instant.now());
        session.setCloseReason(reason);
        session.setLeaseExpiresAt(Instant.now());
        sessions.save(session);
    }

    private String buildPrompt(SystemConfig config, PromptProfile profile, AppUser user, String manualClientContext) {
        StringBuilder out = new StringBuilder(4096);
        append(out, "ОБЩИЕ ПРАВИЛА", config.getGlobalPrompt());
        append(out, "РОЛЬ / СЦЕНАРИЙ", profile.getSystemPrompt());
        append(out, "БАЗА ЗНАНИЙ", profile.getKnowledgeBase());
        append(out, "ПЕРСОНАЛЬНЫЕ НАСТРОЙКИ МЕНЕДЖЕРА", user.getCustomInstructions());
        if (config.isFeatureManualClientContext()) append(out, "КОНТЕКСТ КЛИЕНТА", manualClientContext);
        append(out, "НЕИЗМЕНЯЕМЫЙ ПРОТОКОЛ PRODAMUS", CLIENT_DIALOG_PROTOCOL);
        return out.toString().trim();
    }

    private void append(StringBuilder out, String title, String value) {
        if (value == null || value.isBlank()) return;
        if (!out.isEmpty()) out.append("\n\n");
        out.append("### ").append(title).append("\n").append(value.trim());
    }

    private String trim(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }

    private record Reservation(UUID sessionId, AppUser user, PromptProfile prompt, AiCredential credential) {}
    public record SessionDescriptor(UUID sessionId, String ephemeralToken, Instant tokenExpiresAt,
                                    Instant newSessionExpiresAt, String websocketUrl, String model) {}
}
