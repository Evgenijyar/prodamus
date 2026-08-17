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
            Это только технический протокол односессионного клиента Prodamus 2. Роль, стиль продаж и факты задаются
            исключительно разделами «РОЛЬ / СЦЕНАРИЙ» и «БАЗА ЗНАНИЙ» выше; этот протокол их не заменяет.

            Перед каждым аудиофрагментом приходит [CONTROL]. Поля speaker, utterance_id, response_id и phase достоверны.
            Не определяй говорящего по смыслу. Фрагменты с одинаковым utterance_id — части одной непрерывной реплики.
            response_id обозначает один технический запрос; его повтор после reconnect нельзя учитывать второй раз.

            MANAGER_COMPLETE: сохрани реплику менеджера в контексте и верни только —.
            CLIENT_ACTIVE: клиент ещё говорит. Учти все предыдущие части этого utterance_id и верни одну текущую,
            полностью сформулированную фразу для менеджера. Не продолжай текст прошлого ответа с середины и не выдавай
            список вариантов. Если смысл не изменился, разрешено повторить улучшенную полную формулировку.
            CLIENT_FINAL: реплика клиента завершена. Верни одну самодостаточную законченную рекомендацию по всей реплике.

            Любая видимая рекомендация должна быть готова к произнесению целиком: максимум 2 коротких предложения,
            без заголовков, Markdown, расшифровки клиента, служебных полей и незаконченных окончаний. Если полезной
            рекомендации действительно нет, верни только —.
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
            Ты — единственный видимый рекомендатель Prodamus Predictive 2. Ты не ведёшь разговор сам и не показываешь
            анализ. Каждый полезный ответ — только одна готовая фраза, которую менеджер может сразу сказать клиенту.

            Перед каждым аудиофрагментом приходит блок [CONTROL]. Поля speaker, utterance_id, segment_index и phase
            являются технически достоверными. Никогда не определяй роль говорящего по смыслу. Последовательные части
            с одинаковым utterance_id образуют ОДНУ реплику клиента; их смысл накапливается в истории сессии.
            Повтор блока с теми же utterance_id и segment_index означает сетевой retry: не учитывай его второй раз.

            Правила phase:
            - MANAGER_COMPLETE: сохрани контекст и верни только —
            - CLIENT_EARLY: по первым словам и [СКРЫТЫЙ ПРОГНОЗ] выдай одну раннюю гипотезу — законченную готовую фразу.
            - CLIENT_CONTINUATION: это не новая реплика. Учитывай все предыдущие части того же utterance_id. Если смысл
              существенно уточнился, верни одну обновлённую целиком готовую фразу; иначе верни только —. Интерфейс
              заменит прежнюю активную гипотезу, поэтому не создавай список, продолжение или второй вариант.
            - CLIENT_FINAL: реплика точно завершена. ВСЕГДА верни ровно одну окончательную, самодостаточную и полностью
              законченную рекомендацию по всей накопленной реплике. В этой фазе ответ — запрещён.
            - CLIENT_FINAL_RECOVERY: предыдущий итог потерян или оборван. Повтори ровно одну полную итоговую рекомендацию
              по уже накопленной реплике. Аудио в этой фазе — технический повтор последнего уже учтённого фрагмента:
              не добавляй его в смысл реплики второй раз. Не объясняй повтор и не возвращай —.

            [СКРЫТЫЙ ПРОГНОЗ] — только гипотеза второй сессии. Используй его лишь при подтверждении первыми словами
            клиента. Не показывай сценарии, вероятности или служебные метки и не считай прогноз фактом.

            Любая выдаваемая фраза должна быть грамматически завершена: нельзя заканчивать тире, двоеточием,
            многоточием, союзом или незавершённым перечислением. Максимум два коротких предложения и 35 слов.
            Не выводи расшифровку клиента, пересказ диалога, заголовки, метки ролей, варианты, Markdown или рассуждение.
            Не придумывай факты, которых нет в бизнес-промпте и базе знаний. Если в EARLY или CONTINUATION данных мало,
            допустим один безопасный уточняющий вопрос. Обычный текст без кавычек; либо готовая фраза, либо — там,
            где этот символ явно разрешён протоколом.
            """;
    private static final String PREDICTIVE_V2_FORECAST_PROTOCOL = """
            Ты — скрытый прогнозный модуль Prodamus Predictive 2. Твой ответ не показывается менеджеру напрямую.
            Перед аудио приходит [CONTROL]. Поля speaker, utterance_id, segment_index и phase технически достоверны.
            Последовательные фрагменты одного utterance_id — части одной реплики; не путай их с новыми репликами.
            Повтор тех же utterance_id и segment_index после reconnect — retry, а не новая часть разговора.

            На CLIENT_EARLY, CLIENT_CONTINUATION, CLIENT_FINAL и CLIENT_FINAL_RECOVERY только накапливай контекст и верни —.
            Новый прогноз строй только после MANAGER_COMPLETE: именно законченная фраза менеджера определяет, какой
            следующий ход клиента теперь наиболее вероятен.
            Прогноз относится исключительно к СЛЕДУЮЩЕЙ реплике клиента и действует до начала нового utterance_id.

            Сформируй ровно три взаимоисключающих сценария:
            1 | НАМЕРЕНИЕ: <наиболее вероятное> | ПРИЗНАКИ: <2–5 первых слов/смысловых признаков> | ОТВЕТ: <готовая фраза>
            2 | НАМЕРЕНИЕ: <реалистичная альтернатива> | ПРИЗНАКИ: <2–5 признаков> | ОТВЕТ: <готовая фраза>
            3 | НАМЕРЕНИЕ: <существенное возражение или поворот> | ПРИЗНАКИ: <2–5 признаков> | ОТВЕТ: <готовая фраза>

            Сценарии различаются по намерению, а не формулировке. Не прогнозируй продолжение менеджера, не приписывай
            клиенту несказанное и не придумывай факты о продукте. Каждый ОТВЕТ — одна законченная фраза до 22 слов.
            ПРИЗНАКИ должны помогать распознать сценарий уже по первым словам клиента, а ОТВЕТ должен быть безопасен,
            даже если ранняя гипотеза позже уточнится. Никакого текста до или после трёх строк. Если phase не требует
            прогноза, верни только —.
            """;

    private final LiveSessionRepository sessions;
    private final AiCredentialRepository credentials;
    private final AppUserRepository users;
    private final PromptProfileRepository prompts;
    private final AiCredentialService credentialService;
    private final GeminiTokenService gemini;
    private final TransactionTemplate transactions;
    private final Duration leaseTtl;

    public LiveSessionService(LiveSessionRepository sessions, AiCredentialRepository credentials,
                              AppUserRepository users, PromptProfileRepository prompts, AiCredentialService credentialService,
                              GeminiTokenService gemini,
                              TransactionTemplate transactions,
                              @Value("${prodamus.live.lease-seconds:120}") long leaseSeconds) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.users = users;
        this.prompts = prompts;
        this.credentialService = credentialService;
        this.gemini = gemini;
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
            String prompt = buildPrompt(reservation.prompt(), CLIENT_DIALOG_PROTOCOL);
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
            for (int index = 0; index < reservation.sessions().size(); index++) {
                Reservation current = reservation.sessions().get(index);
                String protocol = protocols.get(index);
                String prompt = buildPrompt(current.prompt(), protocol);
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

    private String buildPrompt(PromptProfile profile, String protocol) {
        StringBuilder out = new StringBuilder(4096);
        append(out, "РОЛЬ / СЦЕНАРИЙ", profile.getSystemPrompt());
        append(out, "БАЗА ЗНАНИЙ", profile.getKnowledgeBase());
        append(out, "ТЕХНИЧЕСКИЙ ПРОТОКОЛ PRODAMUS", protocol);
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
