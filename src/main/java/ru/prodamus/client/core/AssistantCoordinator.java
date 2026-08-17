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
import ru.prodamus.client.server.BackendClient;
import ru.prodamus.client.server.BackendClient.LiveSessionDescriptor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private volatile GeminiLiveClient gemini;
    private volatile WindowsAudioService.WasapiCapture microphone;
    private volatile WindowsAudioService.WasapiCapture loopback;
    private volatile UtteranceDetector microphoneDetector;
    private volatile UtteranceDetector loopbackDetector;
    private volatile AssistantListener listener;

    public AssistantCoordinator(WindowsAudioService audioService, BackendClient backend, ObjectMapper mapper,
                                @Qualifier("assistantExecutor") Executor executor) {
        this.audioService = audioService;
        this.backend = backend;
        this.mapper = mapper;
        this.executor = executor;
    }

    public void start(AppSettings settings, long promptProfileId, String clientContext, AssistantListener listener) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Start ignored: assistant is already running");
            return;
        }
        this.listener = listener;
        listener.onRunningChanged(true);
        listener.onStatus("Получаю ключ Gemini…");
        executor.execute(() -> doStart(settings, promptProfileId, clientContext));
    }

    private void doStart(AppSettings settings, long promptProfileId, String clientContext) {
        try {
            validate(settings, promptProfileId);

            // Единственный серверный запрос, относящийся к разговору: backend проверяет
            // пользователя и роль, затем выдаёт ограниченный временный ключ Gemini.
            LiveSessionDescriptor descriptor = backend.startLiveSession(
                    promptProfileId, clientContext == null ? "" : clientContext.trim());

            // После получения ключа весь разговор идёт напрямую из Windows-клиента
            // в Gemini Live. Backend больше не участвует в активной сессии.
            gemini = new GeminiLiveClient(mapper, new GeminiEvents());
            gemini.connect(descriptor);

            microphoneDetector = new UtteranceDetector(SpeakerRole.MANAGER, settings.vadThreshold(),
                    settings.silenceMillis(), this::sendUtterance);
            loopbackDetector = new UtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                    settings.silenceMillis(), this::sendUtterance);
            microphone = audioService.captureMicrophone(settings.microphoneDeviceId(),
                    microphoneDetector::accept, this::fail);
            loopback = audioService.captureLoopback(settings.loopbackDeviceId(),
                    loopbackDetector::accept, this::fail);
            microphone.start();
            loopback.start();
            listener.onStatus("Слушаю звонок");
            log.info("Prodamus started: backend detached, Gemini WebSocket and both audio captures active");
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    private void sendUtterance(SpeakerRole role, byte[] audio) {
        GeminiLiveClient active = gemini;
        if (!running.get() || active == null || audio == null || audio.length == 0) return;
        log.debug("Utterance queued: role={}, bytes={}", role, audio.length);
        utteranceSender.execute(() -> {
            if (!running.get() || gemini != active) return;
            try {
                active.sendUtterance(role, audio);
            } catch (Throwable throwable) {
                fail(throwable);
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
            if (listener != null) listener.onSuggestion(text, complete);
        }
        @Override public void onTranscript(String text) { if (listener != null) listener.onTranscript(text); }
        @Override public void onError(Throwable error) { fail(error); }
    }
}
