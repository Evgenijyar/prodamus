package ru.prodamus.client.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.prodamus.client.audio.SpeakerRole;
import ru.prodamus.client.audio.UtteranceDetector;
import ru.prodamus.client.audio.UtteranceDetector.SpeechSegment;
import ru.prodamus.client.audio.WindowsAudioService;
import ru.prodamus.client.config.AppSettings;
import ru.prodamus.client.gemini.GeminiEventListener;
import ru.prodamus.client.gemini.GeminiLiveClient;
import ru.prodamus.client.server.BackendClient;
import ru.prodamus.client.server.BackendClient.LiveSessionDescriptor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AssistantCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AssistantCoordinator.class);

    private final WindowsAudioService audioService;
    private final BackendClient backend;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ExecutorService utteranceSender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("gemini-utterance-sender").daemon(true).factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong conversationSequence = new AtomicLong();
    private final AtomicLong responseSequence = new AtomicLong();
    private final AtomicReference<SuggestionScope> activeSuggestion = new AtomicReference<>();

    private volatile GeminiLiveClient gemini;
    private volatile WindowsAudioService.WasapiCapture microphone;
    private volatile WindowsAudioService.WasapiCapture loopback;
    private volatile UtteranceDetector microphoneDetector;
    private volatile UtteranceDetector loopbackDetector;
    private volatile AssistantListener listener;
    private volatile long conversationId;

    public AssistantCoordinator(WindowsAudioService audioService, BackendClient backend, ObjectMapper mapper,
                                @Qualifier("assistantExecutor") Executor executor) {
        this.audioService = audioService;
        this.backend = backend;
        this.mapper = mapper;
        this.executor = executor;
    }

    public void start(AppSettings settings, long promptProfileId, AssistantListener listener) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Start ignored: assistant is already running");
            return;
        }
        this.listener = listener;
        conversationId = conversationSequence.incrementAndGet();
        activeSuggestion.set(null);
        listener.onRunningChanged(true);
        listener.onStatus("Получаю ключ Gemini…");
        executor.execute(() -> doStart(settings, promptProfileId));
    }

    private void doStart(AppSettings settings, long promptProfileId) {
        try {
            validate(settings, promptProfileId);

            // Единственный серверный запрос, относящийся к разговору: backend проверяет
            // пользователя и роль, затем выдаёт ограниченный временный ключ Gemini.
            LiveSessionDescriptor descriptor = backend.startLiveSession(promptProfileId);

            // После получения ключа весь разговор идёт напрямую из Windows-клиента
            // в Gemini Live. Backend больше не участвует в активной сессии.
            gemini = new GeminiLiveClient(mapper, new GeminiEvents());
            gemini.connect(descriptor);

            microphoneDetector = new UtteranceDetector(SpeakerRole.MANAGER, settings.vadThreshold(),
                    settings.silenceMillis(), 0, this::sendSegment);
            int customerSegmentMillis = settings.activeListening()
                    ? settings.activeListeningIntervalSeconds() * 1_000 : 0;
            loopbackDetector = new UtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                    settings.silenceMillis(), customerSegmentMillis, this::sendSegment);
            microphone = audioService.captureMicrophone(settings.microphoneDeviceId(),
                    microphoneDetector::accept, this::fail);
            loopback = audioService.captureLoopback(settings.loopbackDeviceId(),
                    loopbackDetector::accept, this::fail);
            microphone.start();
            loopback.start();
            listener.onStatus("Слушаю звонок");
            log.info("Prodamus started: backend detached, activeListening={}, customerSegmentMs={}",
                    settings.activeListening(), customerSegmentMillis);
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    private void sendSegment(SpeechSegment segment) {
        GeminiLiveClient active = gemini;
        if (segment == null) return;
        SpeakerRole role = segment.role();
        byte[] audio = segment.audio();
        if (!running.get() || active == null || audio == null) return;
        // После активных фрагментов финальная граница может состоять только из тишины
        // либо вообще не содержать аудио при принудительном flush. CONTROL всё равно
        // обязан дойти до Gemini, иначе итоговая рекомендация не будет сформирована.
        if (audio.length == 0 && !segment.finalSegment()) return;
        long utteranceId = (conversationId << 32) | (segment.utteranceId() & 0xffff_ffffL);
        long responseId = responseSequence.incrementAndGet();
        String phase = role == SpeakerRole.MANAGER ? "MANAGER_COMPLETE"
                : segment.finalSegment() ? "CLIENT_FINAL" : "CLIENT_ACTIVE";
        String control = "[CONTROL]\n"
                + "speaker=" + role.name() + "\n"
                + "utterance_id=" + utteranceId + "\n"
                + "response_id=" + responseId + "\n"
                + "phase=" + phase + "\n"
                + "[/CONTROL]";
        SuggestionScope scope = role == SpeakerRole.CUSTOMER
                ? new SuggestionScope(utteranceId, responseId, segment.finalSegment()) : null;
        log.debug("Utterance queued: role={}, utterance={}, response={}, final={}, bytes={}",
                role, utteranceId, responseId, segment.finalSegment(), audio.length);
        utteranceSender.execute(() -> {
            if (!running.get() || gemini != active) return;
            activeSuggestion.set(scope);
            try {
                active.sendUtteranceAndAwait(role, audio, control);
            } catch (Throwable throwable) {
                fail(throwable);
            } finally {
                activeSuggestion.compareAndSet(scope, null);
            }
        });
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Prodamus assistant stopping");
        if (microphoneDetector != null) microphoneDetector.flush();
        if (loopbackDetector != null) loopbackDetector.flush();
        if (microphone != null) microphone.close();
        if (loopback != null) loopback.close();
        if (gemini != null) gemini.close();
        microphoneDetector = null;
        loopbackDetector = null;
        microphone = null;
        loopback = null;
        gemini = null;
        activeSuggestion.set(null);

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
        if (settings == null) throw new IllegalArgumentException("Не загружены локальные настройки");
        if (promptProfileId <= 0) throw new IllegalArgumentException("Выберите роль перед стартом разговора");
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

    @PreDestroy
    public void destroy() {
        stop();
        utteranceSender.shutdownNow();
    }

    private final class GeminiEvents implements GeminiEventListener {
        @Override public void onStatus(String status) { if (listener != null) listener.onStatus(status); }
        @Override public void onSuggestion(String text, boolean complete) {
            AssistantListener current = listener;
            SuggestionScope scope = activeSuggestion.get();
            if (current != null && scope != null) {
                current.onSuggestion(scope.utteranceId(), scope.responseId(), scope.utteranceFinal(), text, complete);
            }
        }
        @Override public void onTranscript(String text) { if (listener != null) listener.onTranscript(text); }
        @Override public void onError(Throwable error) { fail(error); }
    }

    private record SuggestionScope(long utteranceId, long responseId, boolean utteranceFinal) { }
}
