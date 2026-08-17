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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final String PREDICTIVE_SINGLE_PROTOCOL = """
            Ты — экспериментальный предиктивный ассистент менеджера по продажам во время живого звонка.
            Ты получаешь по очереди реплики с метками [КЛИЕНТ] и [МЕНЕДЖЕР]. Несколько фрагментов [КЛИЕНТ]
            могут быть частями одной продолжающейся реплики — всегда учитывай их общий смысл и весь диалог.

            После значимой реплики дай менеджеру одновременно два уровня помощи:
            1. СЕЙЧАС — одна короткая фраза или вопрос, который полезно произнести немедленно.
            2. ПРОГНОЗ — наиболее вероятный следующий ход, сомнение или возражение клиента и короткая подготовка к нему.

            Формат ответа:
            СЕЙЧАС: <готовая к произнесению фраза>
            ПРОГНОЗ: <что вероятнее всего последует>
            ЗАРАНЕЕ: <короткая заготовка ответа или следующий вопрос>

            Не пересказывай разговор, не приветствуй, не объясняй механику и не показывай служебные метки.
            Пиши по-русски, максимально конкретно, не более пяти коротких строк. Если новой полезной подсказки нет, ответь: —
            """;
    private static final String PREDICTIVE_TACTICAL_PROTOCOL = """
            Ты — быстрая тактическая сессия экспериментального ассистента Prodamus.
            Ты получаешь реплики [КЛИЕНТ] и [МЕНЕДЖЕР] и сохраняешь весь контекст разговора.
            После значимой реплики мгновенно предложи только одну лучшую фразу или один вопрос,
            который менеджеру полезно произнести прямо сейчас. Не анализируй вслух, не прогнозируй,
            не пересказывай разговор и не показывай служебные метки. Ответ — максимум два коротких
            предложения на русском языке, готовых к произнесению. Если подсказка не нужна, ответь: —
            """;
    private static final String PREDICTIVE_FORECAST_PROTOCOL = """
            Ты — фоновая предиктивная сессия ассистента менеджера по продажам.
            Ты получаешь тот же живой диалог с метками [КЛИЕНТ] и [МЕНЕДЖЕР], но не дублируешь
            немедленную тактическую подсказку. Твоя задача — опережать разговор.

            На основании потребностей, этапа продажи, формулировок и поведения собеседников выбери
            наиболее вероятный следующий ход клиента: вопрос, сомнение, возражение, запрос цены,
            паузу в решении или готовность перейти дальше. Подготовь менеджера заранее.

            Формат ответа:
            ПРОГНОЗ: <один наиболее вероятный следующий ход клиента>
            ЗАРАНЕЕ: <короткая готовая фраза, вопрос или способ обработки>
            ЗАПАСНОЙ ХОД: <только если действительно полезен второй вероятный сценарий>

            Не пересказывай диалог, не приветствуй, не объясняй механику, не показывай служебные метки
            и не выдавай больше трёх коротких строк. Если прогноз пока не несёт практической пользы, ответь: —
            """;
    private static final String PREDICTIVE_V2_RECOMMENDER_PROTOCOL = """
            Ты — единственный видимый менеджеру рекомендатель Prodamus Predictive 2 во время живого звонка.
            Ты получаешь реплики [КЛИЕНТ] и [МЕНЕДЖЕР]. Реплика клиента может приходить короткими последовательными
            фрагментами: первый фрагмент содержит только первые слова, следующие продолжают ту же реплику.

            Внутри диалога ты также можешь получать служебное текстовое сообщение [СКРЫТЫЙ ПРОГНОЗ]. Оно содержит
            три вероятных сценария следующего хода клиента, заранее построенных второй AI-сессией. Никогда не показывай
            прогноз, варианты, вероятности, анализ или служебные метки. Используй их только как внутреннюю гипотезу.
            На сообщение [СКРЫТЫЙ ПРОГНОЗ] само по себе всегда отвечай одним символом: —

            Перед фрагментом клиента приложение передаёт [РЕЖИМ ОТВЕТА: ГИПОТЕЗА] или [РЕЖИМ ОТВЕТА: ИТОГ].
            В режиме ГИПОТЕЗА выдай одну полностью законченную раннюю фразу. Даже если данных мало, не обрывай её
            на полуслове: менеджер может уже начать произносить её. Новые данные не переписывают и не отменяют ранее
            выданную гипотезу — они позволяют добавить следующую, более точную законченную фразу.
            В режиме ИТОГ, после окончания реплики клиента, выдай одну окончательную рекомендацию на основании всей
            реплики. Она добавляется после гипотез и не должна ссылаться на них или объяснять, что изменилось.

            Когда начинается новый фрагмент [КЛИЕНТ], сопоставь первые слова с тремя скрытыми сценариями и немедленно
            выдай ОДНУ лучшую фразу, которую менеджеру полезно произнести. Не жди конца длинной реплики, если намерение
            уже различимо. Когда приходят следующие фрагменты той же реплики, выдавай ниже новую полностью законченную
            рекомендацию с учётом всего услышанного, не переписывая предыдущую. В каждом ответе только одна актуальная фраза.
            Если первые слова ещё неоднозначны, дай безопасный уточняющий вопрос, а не выдумывай факт.

            После [МЕНЕДЖЕР] обновляй контекст, но не давай подсказку без практической необходимости.
            Ответ — максимум два коротких предложения на русском языке, готовых к произнесению.
            Никаких заголовков, нумерации, пояснений, пересказа диалога или меток. Если полезной подсказки нет: —
            """;
    private static final String PREDICTIVE_V2_FORECAST_PROTOCOL = """
            Ты — невидимый планировщик Prodamus Predictive 2. Твои ответы никогда напрямую не показываются менеджеру.
            Ты получаешь весь разговор с метками [КЛИЕНТ] и [МЕНЕДЖЕР], включая последовательные части длинной реплики.

            После каждого значимого изменения диалога построй ровно ТРИ взаимоисключающих и практически полезных
            сценария того, что клиент вероятнее всего скажет дальше или как продолжит уже начатую реплику. Опирайся
            на этап продажи, формулировки клиента, предыдущие вопросы и возражения, роль и базу знаний. Для каждого
            сценария укажи короткие речевые признаки, по которым его можно распознать с первых слов, и одну готовую
            реакцию менеджера. Сценарии должны различаться по намерению, а не быть перефразировками.

            Строгий формат:
            1 | НАМЕРЕНИЕ: <кратко> | ПРИЗНАКИ: <первые слова/смысл> | ОТВЕТ: <готовая фраза>
            2 | НАМЕРЕНИЕ: <кратко> | ПРИЗНАКИ: <первые слова/смысл> | ОТВЕТ: <готовая фраза>
            3 | НАМЕРЕНИЕ: <кратко> | ПРИЗНАКИ: <первые слова/смысл> | ОТВЕТ: <готовая фраза>

            Не добавляй вступление, вывод, заголовок, вероятности или четвёртый вариант. Пиши по-русски и компактно.
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

    public PredictiveSessionBundle startPredictive(Long userId, String authenticatedDeviceId, Long promptProfileId,
                                                    String requestedDeviceId, String clientVersion,
                                                    String manualClientContext, boolean dualSession) {
        List<String> protocols = dualSession
                ? List.of(PREDICTIVE_TACTICAL_PROTOCOL, PREDICTIVE_FORECAST_PROTOCOL)
                : List.of(PREDICTIVE_SINGLE_PROTOCOL);
        return startPredictiveBundle(userId, authenticatedDeviceId, promptProfileId, requestedDeviceId,
                clientVersion, manualClientContext, dualSession ? "DUAL" : "SINGLE", protocols);
    }

    public PredictiveSessionBundle startPredictiveV2(Long userId, String authenticatedDeviceId, Long promptProfileId,
                                                      String requestedDeviceId, String clientVersion,
                                                      String manualClientContext) {
        return startPredictiveBundle(userId, authenticatedDeviceId, promptProfileId, requestedDeviceId,
                clientVersion, manualClientContext, "PREDICTIVE_V2",
                List.of(PREDICTIVE_V2_RECOMMENDER_PROTOCOL, PREDICTIVE_V2_FORECAST_PROTOCOL));
    }

    private PredictiveSessionBundle startPredictiveBundle(Long userId, String authenticatedDeviceId,
                                                           Long promptProfileId, String requestedDeviceId,
                                                           String clientVersion, String manualClientContext,
                                                           String mode, List<String> protocols) {
        if (requestedDeviceId != null && !requestedDeviceId.isBlank()
                && !authenticatedDeviceId.equals(requestedDeviceId.trim())) {
            throw ApiException.forbidden("deviceId не совпадает с авторизованным устройством.");
        }
        int requiredCredentials = protocols.size();
        PredictiveReservation reservation = transactions.execute(status -> reservePredictive(
                userId, authenticatedDeviceId, promptProfileId, clientVersion, requiredCredentials));
        if (reservation == null) {
            throw ApiException.unavailable("SESSION_RESERVATION_FAILED",
                    "Не удалось зарезервировать предиктивные AI-сессии.");
        }

        List<SessionDescriptor> descriptors = new ArrayList<>(requiredCredentials);
        try {
            SystemConfig config = systemConfigService.get();
            for (int index = 0; index < reservation.sessions().size(); index++) {
                Reservation current = reservation.sessions().get(index);
                String protocol = protocols.get(index);
                String prompt = buildPrompt(config, current.prompt(), current.user(), manualClientContext, protocol);
                GeminiTokenService.TokenResult token = gemini.createConstrainedToken(
                        credentialService.decrypt(current.credential()), current.prompt().getModel(), prompt);
                transactions.executeWithoutResult(status -> activate(current.sessionId(), token.expiresAt()));
                descriptors.add(new SessionDescriptor(current.sessionId(), token.ephemeralToken(), token.expiresAt(),
                        token.newSessionExpiresAt(), token.websocketUrl(), token.model()));
            }
            return new PredictiveSessionBundle(mode, descriptors.getFirst(),
                    descriptors.size() > 1 ? descriptors.get(1) : null);
        } catch (RuntimeException ex) {
            transactions.executeWithoutResult(status -> reservation.sessions().forEach(current ->
                    closeInternal(current.sessionId(), "PROVISIONING_ERROR", trim(ex.getMessage(), 480))));
            throw ex;
        }
    }

    private PredictiveReservation reservePredictive(Long userId, String deviceId, Long promptId,
                                                     String clientVersion, int requiredCredentials) {
        AppUser user = users.lockById(userId)
                .orElseThrow(() -> ApiException.notFound("Пользователь не найден."));
        if (!user.isEnabled()) throw ApiException.forbidden("Доступ пользователя отключён.");
        PromptProfile prompt = prompts.findById(promptId)
                .orElseThrow(() -> ApiException.notFound("Выбранная роль не найдена."));
        if (!prompt.isEnabled() || user.getPromptProfiles().stream().noneMatch(p -> p.getId().equals(promptId))) {
            throw ApiException.forbidden("Эта роль недоступна пользователю.");
        }

        Instant now = Instant.now();
        for (LiveSession existing : sessions.findByUser_IdAndStatusIn(userId, LEASED_STATUSES)) {
            closeEntity(existing, "REPLACED", "Выдан новый комплект временных ключей");
        }

        List<AiCredential> selected = new ArrayList<>(requiredCredentials);
        Set<Long> selectedIds = new HashSet<>();
        for (AiCredential candidate : credentials.lockEnabledCredentials()) {
            if (selectedIds.contains(candidate.getId())) continue;
            long active = sessions.countLeasedForCredential(candidate.getId(), now);
            if (active < candidate.getMaxConcurrentSessions()) {
                selected.add(candidate);
                selectedIds.add(candidate.getId());
                if (selected.size() == requiredCredentials) break;
            }
        }
        if (selected.size() < requiredCredentials) {
            String message = requiredCredentials == 2
                    ? "Для двухсессионного режима нужны два разных свободных AI-ключа. Освободите или добавьте ещё один ключ."
                    : "Сейчас нет свободного AI-ключа. Повторите попытку через несколько секунд.";
            throw ApiException.unavailable("NO_AI_CAPACITY", message);
        }

        List<Reservation> reservations = new ArrayList<>(requiredCredentials);
        for (AiCredential credential : selected) {
            LiveSession session = new LiveSession();
            session.setId(UUID.randomUUID());
            session.setUser(user);
            session.setPromptProfile(prompt);
            session.setAiCredential(credential);
            session.setStatus("PROVISIONING");
            session.setDeviceId(deviceId);
            session.setClientVersion(trim(clientVersion, 60));
            session.setPromptVersion(prompt.getVersion());
            session.setStartedAt(now);
            session.setLeaseExpiresAt(now.plus(leaseTtl));
            sessions.saveAndFlush(session);
            reservations.add(new Reservation(session.getId(), user, prompt, credential));
        }
        return new PredictiveReservation(List.copyOf(reservations));
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
        return buildPrompt(config, profile, user, manualClientContext, CLIENT_DIALOG_PROTOCOL);
    }

    private String buildPrompt(SystemConfig config, PromptProfile profile, AppUser user,
                               String manualClientContext, String protocol) {
        StringBuilder out = new StringBuilder(4096);
        append(out, "ОБЩИЕ ПРАВИЛА", config.getGlobalPrompt());
        append(out, "РОЛЬ / СЦЕНАРИЙ", profile.getSystemPrompt());
        append(out, "БАЗА ЗНАНИЙ", profile.getKnowledgeBase());
        append(out, "ПЕРСОНАЛЬНЫЕ НАСТРОЙКИ МЕНЕДЖЕРА", user.getCustomInstructions());
        if (config.isFeatureManualClientContext()) append(out, "КОНТЕКСТ КЛИЕНТА", manualClientContext);
        append(out, "НЕИЗМЕНЯЕМЫЙ ПРОТОКОЛ PRODAMUS", protocol);
        return out.toString().trim();
    }

    private void append(StringBuilder out, String title, String value) {
        if (value == null || value.isBlank()) return;
        if (!out.isEmpty()) out.append("\n\n");
        out.append("### ").append(title).append("\n").append(value.trim());
    }

    private String trim(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }

    private record Reservation(UUID sessionId, AppUser user, PromptProfile prompt, AiCredential credential) {}
    private record PredictiveReservation(List<Reservation> sessions) {}
    public record SessionDescriptor(UUID sessionId, String ephemeralToken, Instant tokenExpiresAt,
                                    Instant newSessionExpiresAt, String websocketUrl, String model) {}
    public record PredictiveSessionBundle(String mode, SessionDescriptor tactical, SessionDescriptor predictive) {}
}
