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
import ru.prodamus.client.server.BackendClient.PredictiveSessionBundle;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AssistantCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AssistantCoordinator.class);

    private final WindowsAudioService audioService;
    private final BackendClient backend;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ExecutorService tacticalSender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("gemini-tactical-sender").daemon(true).factory());
    private final ExecutorService predictiveSender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("gemini-predictive-sender").daemon(true).factory());
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile GeminiLiveClient tacticalGemini;
    private volatile GeminiLiveClient predictiveGemini;
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
            log.warn("Start ignored: predictive assistant is already running");
            return;
        }
        this.listener = listener;
        listener.onRunningChanged(true);
        listener.onStatus(settings.dualSession()
                ? "Получаю два ключа Gemini…" : "Получаю ключ Gemini…");
        executor.execute(() -> doStart(settings, promptProfileId, clientContext));
    }

    private void doStart(AppSettings settings, long promptProfileId, String clientContext) {
        try {
            validate(settings, promptProfileId);
            PredictiveSessionBundle bundle = backend.startPredictiveSession(promptProfileId,
                    clientContext == null ? "" : clientContext.trim(), settings.dualSession());
            if (bundle == null || bundle.tactical() == null) {
                throw new IllegalStateException("Backend не выдал тактическую Gemini-сессию");
            }

            SuggestionKind tacticalKind = bundle.dual() ? SuggestionKind.TACTICAL : SuggestionKind.COMBINED;
            tacticalGemini = new GeminiLiveClient(mapper, new SessionEvents(tacticalKind));
            if (bundle.dual()) {
                predictiveGemini = new GeminiLiveClient(mapper, new SessionEvents(SuggestionKind.PREDICTIVE));
                connectBoth(bundle);
            } else {
                tacticalGemini.connect(bundle.tactical());
            }

            microphoneDetector = new UtteranceDetector(SpeakerRole.MANAGER, settings.vadThreshold(),
                    settings.silenceMillis(), this::sendUtterance);
            int customerSegmentMillis = settings.activeListening()
                    ? settings.activeListeningIntervalSeconds() * 1_000 : 0;
            loopbackDetector = new UtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                    settings.silenceMillis(), customerSegmentMillis, this::sendUtterance);
            microphone = audioService.captureMicrophone(settings.microphoneDeviceId(),
                    microphoneDetector::accept, this::fail);
            loopback = audioService.captureLoopback(settings.loopbackDeviceId(),
                    loopbackDetector::accept, this::fail);
            microphone.start();
            loopback.start();
            listener.onStatus(bundle.dual() ? "Слушаю · тактика + прогноз" : "Слушаю · один предиктивный ключ");
            log.info("Prodamus Predictive started: mode={}, activeListening={}, customerSegmentMs={}",
                    bundle.mode(), settings.activeListening(), customerSegmentMillis);
        } catch (Throwable throwable) {
            fail(unwrap(throwable));
        }
    }

    private void connectBoth(PredictiveSessionBundle bundle) throws Exception {
        listener.onStatus("Подключаю две Gemini-сессии…");
        try (ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> tactical = connections.submit(() -> tacticalGemini.connect(bundle.tactical()));
            Future<?> predictive = connections.submit(() -> predictiveGemini.connect(bundle.predictive()));
            tactical.get();
            predictive.get();
        }
    }

    private void sendUtterance(SpeakerRole role, byte[] audio) {
        GeminiLiveClient tactical = tacticalGemini;
        GeminiLiveClient predictive = predictiveGemini;
        if (!running.get() || tactical == null || audio == null || audio.length == 0) return;
        log.debug("Predictive utterance fan-out: role={}, bytes={}, dual={}", role, audio.length, predictive != null);
        submit(tacticalSender, tactical, role, audio, SuggestionKind.TACTICAL);
        if (predictive != null) submit(predictiveSender, predictive, role, audio, SuggestionKind.PREDICTIVE);
    }

    private void submit(ExecutorService sender, GeminiLiveClient target, SpeakerRole role, byte[] audio,
                        SuggestionKind kind) {
        sender.execute(() -> {
            if (!running.get()) return;
            GeminiLiveClient current = kind == SuggestionKind.PREDICTIVE ? predictiveGemini : tacticalGemini;
            if (current != target) return;
            try {
                target.sendUtterance(role, audio);
            } catch (Throwable throwable) {
                fail(throwable);
            }
        });
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Prodamus Predictive stopping");
        if (microphoneDetector != null) microphoneDetector.flush();
        if (loopbackDetector != null) loopbackDetector.flush();
        if (microphone != null) microphone.close();
        if (loopback != null) loopback.close();
        if (tacticalGemini != null) tacticalGemini.close();
        if (predictiveGemini != null) predictiveGemini.close();
        microphoneDetector = null;
        loopbackDetector = null;
        microphone = null;
        loopback = null;
        tacticalGemini = null;
        predictiveGemini = null;

        AssistantListener current = listener;
        if (current != null) {
            current.onRunningChanged(false);
            current.onStatus("Остановлено");
        }
    }

    public boolean isRunning() { return running.get(); }

    private void validate(AppSettings settings, long promptProfileId) {
        if (settings == null) throw new IllegalArgumentException("Не загружены локальные настройки");
        if (promptProfileId <= 0) throw new IllegalArgumentException("Выберите роль перед стартом разговора");
    }

    private void fail(Throwable throwable) {
        if (!running.get()) return;
        log.error("Predictive assistant failure", throwable);
        AssistantListener current = listener;
        if (current != null) current.onError(rootMessage(throwable));
        stop();
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof ExecutionException || current.getClass().getSimpleName().equals("CompletionException"))
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @PreDestroy
    public void destroy() {
        stop();
        tacticalSender.shutdownNow();
        predictiveSender.shutdownNow();
    }

    private final class SessionEvents implements GeminiEventListener {
        private final SuggestionKind kind;

        private SessionEvents(SuggestionKind kind) { this.kind = kind; }

        @Override
        public void onStatus(String status) {
            AssistantListener current = listener;
            if (current == null) return;
            if (kind != SuggestionKind.PREDICTIVE) current.onStatus(status);
            else if (status != null && status.startsWith("Переподключение")) {
                current.onStatus("Восстанавливаю предиктивную сессию…");
            }
        }

        @Override
        public void onSuggestion(String text, boolean complete) {
            AssistantListener current = listener;
            if (current != null) current.onSuggestion(kind, text, complete);
        }

        @Override
        public void onTranscript(String text) {
            AssistantListener current = listener;
            if (current != null && kind != SuggestionKind.PREDICTIVE) current.onTranscript(text);
        }

        @Override public void onError(Throwable error) { fail(error); }
    }
}
