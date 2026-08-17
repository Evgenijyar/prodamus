package ru.prodamus.client.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.prodamus.client.audio.SpeakerRole;
import ru.prodamus.client.audio.UtteranceDetector;
import ru.prodamus.client.audio.WindowsAudioService;
import ru.prodamus.client.config.AppSettings;
import ru.prodamus.client.gemini.GeminiEventListener;
import ru.prodamus.client.gemini.GeminiLiveClient;
import ru.prodamus.client.gemini.GeminiLiveClient.HistoryTurn;
import ru.prodamus.client.server.BackendClient;
import ru.prodamus.client.server.BackendClient.LiveSessionDescriptor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AssistantCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AssistantCoordinator.class);
    private static final int MAX_RECOVERY_TURNS = 48;
    private static final int MAX_RECOVERY_CHARS = 30_000;

    private final WindowsAudioService audioService;
    private final BackendClient backend;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ExecutorService audioSender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("prodamus-live-audio-sender").daemon(true).factory());
    private final ScheduledExecutorService sessionScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("prodamus-session-heartbeat").daemon(true).factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger heartbeatFailures = new AtomicInteger();
    private final Object recoveryHistoryLock = new Object();
    private final Deque<HistoryTurn> recoveryHistory = new ArrayDeque<>();

    private volatile GeminiLiveClient gemini;
    private volatile WindowsAudioService.WasapiCapture microphone;
    private volatile WindowsAudioService.WasapiCapture loopback;
    private volatile UtteranceDetector microphoneDetector;
    private volatile UtteranceDetector loopbackDetector;
    private volatile AssistantListener listener;
    private volatile LiveSessionDescriptor session;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile String clientContext = "";

    public AssistantCoordinator(WindowsAudioService audioService, BackendClient backend, ObjectMapper mapper,
                                @Qualifier("assistantExecutor") Executor executor) {
        this.audioService = audioService;
        this.backend = backend;
        this.mapper = mapper;
        this.executor = executor;
    }

    public void start(AppSettings settings, long promptProfileId, String clientContext, AssistantListener listener) {
        if (!running.compareAndSet(false, true)) return;
        this.listener = listener;
        this.clientContext = clientContext == null ? "" : clientContext.trim();
        clearRecoveryHistory();
        listener.onRunningChanged(true);
        listener.onStatus("Создаю защищённую AI-сессию…");
        executor.execute(() -> doStart(settings, promptProfileId));
    }

    private void doStart(AppSettings settings, long promptProfileId) {
        try {
            validate(settings, promptProfileId);
            session = backend.startLiveSession(promptProfileId, clientContext);
            log.info("Server Live session allocated: id={}, tokenExpires={}",
                    session.sessionId(), session.tokenExpiresAt());

            gemini = new GeminiLiveClient(mapper, new GeminiEvents(), new GeminiRecovery());
            gemini.connect(session);

            UtteranceDetector.StreamListener audioStream = new LiveAudioStream();
            microphoneDetector = new UtteranceDetector(SpeakerRole.MANAGER, settings.vadThreshold(),
                    settings.silenceMillis(), audioStream);
            loopbackDetector = new UtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                    settings.silenceMillis(), audioStream);
            microphone = audioService.captureMicrophone(settings.microphoneDeviceId(), microphoneDetector::accept, this::fail);
            loopback = audioService.captureLoopback(settings.loopbackDeviceId(), loopbackDetector::accept, this::fail);
            microphone.start();
            loopback.start();
            startHeartbeat();
            listener.onStatus("Слушаю звонок");
            log.info("Prodamus assistant started successfully");
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    private void startHeartbeat() {
        LiveSessionDescriptor active = session;
        if (active == null) return;
        long seconds = Math.max(10, active.heartbeatEverySeconds());
        heartbeatTask = sessionScheduler.scheduleWithFixedDelay(this::heartbeat, seconds, seconds, TimeUnit.SECONDS);
    }

    private void heartbeat() {
        if (!running.get() || session == null) return;
        try {
            backend.heartbeat(session.sessionId());
            heartbeatFailures.set(0);
            LiveSessionDescriptor current = session;
            if (current.tokenExpiresAt() != null && current.tokenExpiresAt().isBefore(Instant.now().plusSeconds(300))) {
                LiveSessionDescriptor renewed = backend.renewLiveToken(current.sessionId(), clientContext);
                session = renewed;
                GeminiLiveClient activeGemini = gemini;
                if (activeGemini != null) activeGemini.rotateDescriptor(renewed);
                log.info("Gemini ephemeral token renewed for active session {}", renewed.sessionId());
            }
        } catch (Throwable ex) {
            int failures = heartbeatFailures.incrementAndGet();
            log.warn("Backend heartbeat failed ({}/3): {}", failures, ex.toString());
            AssistantListener current = listener;
            if (current != null && failures < 3) current.onStatus("Связь с сервером нестабильна…");
            if (failures >= 3) fail(new IllegalStateException("Потеряна связь с сервером Prodamus: " + rootMessage(ex), ex));
        }
    }

    private void enqueueAudio(GeminiLiveClient active, Runnable action) {
        if (!running.get() || active == null) return;
        audioSender.execute(() -> {
            if (!running.get() || gemini != active) return;
            try {
                action.run();
            } catch (Throwable throwable) {
                fail(throwable);
            }
        });
    }

    private void startUtterance(SpeakerRole role, byte[] initialAudio) {
        GeminiLiveClient active = gemini;
        enqueueAudio(active, () -> active.beginUtterance(role, initialAudio));
    }

    private void streamUtteranceAudio(SpeakerRole role, byte[] audio) {
        GeminiLiveClient active = gemini;
        enqueueAudio(active, () -> active.sendAudioChunk(role, audio));
    }

    private void endUtterance(SpeakerRole role) {
        GeminiLiveClient active = gemini;
        enqueueAudio(active, () -> active.endUtterance(role));
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Prodamus assistant stopping");
        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) task.cancel(false);
        if (microphoneDetector != null) microphoneDetector.flush();
        if (loopbackDetector != null) loopbackDetector.flush();
        if (microphone != null) microphone.close();
        if (loopback != null) loopback.close();
        if (gemini != null) gemini.close();

        LiveSessionDescriptor closing = session;
        session = null;
        microphone = null;
        loopback = null;
        gemini = null;
        microphoneDetector = null;
        loopbackDetector = null;
        heartbeatFailures.set(0);

        if (closing != null) {
            executor.execute(() -> {
                try {
                    backend.closeLiveSession(closing.sessionId(), "Client stop");
                } catch (RuntimeException ex) {
                    log.debug("Server session close failed: {}", ex.getMessage());
                }
            });
        }
        AssistantListener current = listener;
        if (current != null) {
            current.onRunningChanged(false);
            current.onStatus("Остановлено");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void validate(AppSettings settings, long promptProfileId) {
        if (promptProfileId <= 0) throw new IllegalArgumentException("Выберите роль перед стартом разговора");
        if (settings == null) throw new IllegalArgumentException("Не загружены локальные настройки");
    }

    private void fail(Throwable throwable) {
        if (!running.get()) return;
        log.error("Assistant failure", throwable);
        AssistantListener current = listener;
        if (current != null) current.onError(rootMessage(throwable));
        stop();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void rememberUserTurn(SpeakerRole role, String text) {
        if (text == null || text.isBlank()) return;
        rememberHistory(new HistoryTurn("user", "[" + role.label() + "] " + text.trim()));
    }

    private void rememberAssistantTurn(String text) {
        if (text == null || text.isBlank()) return;
        rememberHistory(new HistoryTurn("model", text.trim()));
    }

    private void rememberHistory(HistoryTurn turn) {
        synchronized (recoveryHistoryLock) {
            recoveryHistory.addLast(turn);
            trimRecoveryHistory();
        }
    }

    private void trimRecoveryHistory() {
        while (recoveryHistory.size() > MAX_RECOVERY_TURNS) recoveryHistory.removeFirst();
        int chars = recoveryHistory.stream().mapToInt(turn -> turn.text() == null ? 0 : turn.text().length()).sum();
        while (chars > MAX_RECOVERY_CHARS && recoveryHistory.size() > 2) {
            HistoryTurn removed = recoveryHistory.removeFirst();
            chars -= removed.text() == null ? 0 : removed.text().length();
        }
    }

    private List<HistoryTurn> recoveryHistorySnapshot() {
        synchronized (recoveryHistoryLock) {
            return new ArrayList<>(recoveryHistory);
        }
    }

    private void clearRecoveryHistory() {
        synchronized (recoveryHistoryLock) {
            recoveryHistory.clear();
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
        audioSender.shutdownNow();
        sessionScheduler.shutdownNow();
    }

    private final class LiveAudioStream implements UtteranceDetector.StreamListener {
        @Override
        public void onStarted(SpeakerRole role, byte[] initialAudio) {
            startUtterance(role, initialAudio);
        }

        @Override
        public void onAudio(SpeakerRole role, byte[] audio) {
            streamUtteranceAudio(role, audio);
        }

        @Override
        public void onEnded(SpeakerRole role) {
            endUtterance(role);
        }
    }

    private final class GeminiRecovery implements GeminiLiveClient.RecoverySupport {
        @Override
        public LiveSessionDescriptor renewDescriptor() {
            if (!running.get()) throw new IllegalStateException("Разговор уже остановлен");
            LiveSessionDescriptor current = session;
            if (current == null) throw new IllegalStateException("Серверная Live-сессия отсутствует");
            LiveSessionDescriptor renewed = backend.renewLiveToken(current.sessionId(), clientContext);
            session = renewed;
            log.info("Fresh Gemini token issued for automatic recovery: session={}", renewed.sessionId());
            return renewed;
        }

        @Override
        public List<HistoryTurn> historySnapshot() {
            return recoveryHistorySnapshot();
        }
    }

    private final class GeminiEvents implements GeminiEventListener {
        @Override
        public void onStatus(String status) {
            if (listener != null) listener.onStatus(status);
        }

        @Override
        public void onSuggestion(String text, boolean complete) {
            if (complete) rememberAssistantTurn(text);
            if (listener != null) listener.onSuggestion(text, complete);
        }

        @Override
        public void onTranscript(SpeakerRole role, String text, boolean complete) {
            if (complete) rememberUserTurn(role, text);
            if (listener != null) listener.onTranscript(role, text, complete);
        }

        @Override
        public void onError(Throwable error) {
            fail(error);
        }
    }
}
