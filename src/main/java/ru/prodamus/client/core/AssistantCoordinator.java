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

/** SalesHelper conversation pipeline with Prodamus authentication and reconnect. */
@Service
public class AssistantCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AssistantCoordinator.class);

    private final WindowsAudioService audioService;
    private final BackendClient backend;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ExecutorService utteranceSender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("gemini-utterance-sender").daemon(true).factory());
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("gemini-reconnect").daemon(true).factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean();

    private volatile GeminiLiveClient gemini;
    private volatile WindowsAudioService.WasapiCapture microphone;
    private volatile WindowsAudioService.WasapiCapture loopback;
    private volatile UtteranceDetector microphoneDetector;
    private volatile UtteranceDetector loopbackDetector;
    private volatile AssistantListener listener;
    private volatile long promptProfileId;

    public AssistantCoordinator(WindowsAudioService audioService, BackendClient backend, ObjectMapper mapper,
                                @Qualifier("assistantExecutor") Executor executor) {
        this.audioService = audioService;
        this.backend = backend;
        this.mapper = mapper;
        this.executor = executor;
    }

    public void start(AppSettings settings, long promptProfileId, AssistantListener listener) {
        if (!running.compareAndSet(false, true)) return;
        this.listener = listener;
        this.promptProfileId = promptProfileId;
        reconnecting.set(false);
        listener.onRunningChanged(true);
        executor.execute(() -> doStart(settings));
    }

    private void doStart(AppSettings settings) {
        try {
            if (promptProfileId <= 0) throw new IllegalArgumentException("Выберите роль перед стартом разговора");
            gemini = openGeminiSession();

            // Эти четыре строки повторяют исходную схему SalesHelper: два независимых
            // VAD, одна очередь реплик и никакой дополнительной маршрутизации.
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
            log.info("Prodamus started with the original SalesHelper conversation pipeline");
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    private GeminiLiveClient openGeminiSession() {
        LiveSessionDescriptor descriptor = backend.startLiveSession(promptProfileId);
        GeminiLiveClient client = new GeminiLiveClient(mapper, new GeminiEvents());
        client.connect(descriptor);
        return client;
    }

    private void sendUtterance(SpeakerRole role, byte[] audio) {
        if (!running.get() || audio == null || audio.length == 0) return;
        utteranceSender.execute(() -> deliverUtterance(role, audio));
    }

    private void deliverUtterance(SpeakerRole role, byte[] audio) {
        boolean retried = false;
        while (running.get()) {
            GeminiLiveClient active = awaitConnectedClient();
            if (active == null) return;
            try {
                active.sendUtterance(role, audio);
                return;
            } catch (Throwable throwable) {
                log.warn("Gemini send failed: {}; reconnecting", rootMessage(throwable));
                requestReconnect(throwable);
                if (retried) return;
                retried = true;
            }
        }
    }

    private GeminiLiveClient awaitConnectedClient() {
        while (running.get()) {
            GeminiLiveClient current = gemini;
            if (current != null && !reconnecting.get()) return current;
            try {
                Thread.sleep(40);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void requestReconnect(Throwable cause) {
        if (!running.get() || !reconnecting.compareAndSet(false, true)) return;
        AssistantListener current = listener;
        if (current != null) current.onStatus("Переподключение к Gemini…");
        reconnectExecutor.execute(() -> reconnectLoop(cause));
    }

    private void reconnectLoop(Throwable initialCause) {
        Throwable last = initialCause;
        GeminiLiveClient previous = gemini;
        gemini = null;
        if (previous != null) previous.close();

        for (int attempt = 1; running.get(); attempt++) {
            try {
                GeminiLiveClient replacement = openGeminiSession();
                if (!running.get()) {
                    replacement.close();
                    return;
                }
                gemini = replacement;
                reconnecting.set(false);
                AssistantListener current = listener;
                if (current != null) current.onStatus("Слушаю звонок");
                log.info("Gemini reconnected successfully: attempt={}", attempt);
                return;
            } catch (Throwable throwable) {
                last = throwable;
                log.warn("Gemini reconnect attempt {} failed: {}", attempt, rootMessage(throwable));
                if (attempt >= 8) break;
                try {
                    Thread.sleep(Math.min(2_000L, (attempt - 1L) * 250L));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        reconnecting.set(false);
        if (running.get()) fail(new IllegalStateException(
                "Не удалось переподключиться к Gemini: " + rootMessage(last), last));
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (microphoneDetector != null) microphoneDetector.flush();
        if (loopbackDetector != null) loopbackDetector.flush();
        if (microphone != null) microphone.close();
        if (loopback != null) loopback.close();
        GeminiLiveClient active = gemini;
        gemini = null;
        if (active != null) active.close();
        microphoneDetector = null;
        loopbackDetector = null;
        microphone = null;
        loopback = null;
        reconnecting.set(false);
        AssistantListener current = listener;
        if (current != null) {
            current.onRunningChanged(false);
            current.onStatus("Остановлено");
        }
    }

    public boolean isRunning() { return running.get(); }

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
        reconnectExecutor.shutdownNow();
    }

    private final class GeminiEvents implements GeminiEventListener {
        @Override public void onStatus(String status) { if (listener != null) listener.onStatus(status); }
        @Override public void onSuggestion(String text, boolean complete) {
            if (listener != null) listener.onSuggestion(text, complete);
        }
        @Override public void onTranscript(String text) { if (listener != null) listener.onTranscript(text); }
        @Override public void onError(Throwable error) { requestReconnect(error); }
    }
}
