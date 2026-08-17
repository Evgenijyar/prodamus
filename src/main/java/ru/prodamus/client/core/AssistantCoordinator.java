package ru.prodamus.client.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.prodamus.client.audio.ProgressiveUtteranceDetector;
import ru.prodamus.client.audio.ProgressiveUtteranceDetector.SpeechSegment;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AssistantCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AssistantCoordinator.class);
    private static final int FIRST_WORDS_MILLIS = 900;

    private final WindowsAudioService audioService;
    private final BackendClient backend;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ExecutorService recommenderSender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("gemini-v2-recommender-sender").daemon(true).factory());
    private final ExecutorService forecasterSender = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("gemini-v2-forecaster-sender").daemon(true).factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong forecastSequence = new AtomicLong();
    private final AtomicReference<ForecastSnapshot> latestForecast = new AtomicReference<>();

    private volatile long forwardedForecastVersion;
    private volatile GeminiLiveClient recommender;
    private volatile GeminiLiveClient forecaster;
    private volatile WindowsAudioService.WasapiCapture microphone;
    private volatile WindowsAudioService.WasapiCapture loopback;
    private volatile UtteranceDetector microphoneDetector;
    private volatile ProgressiveUtteranceDetector customerDetector;
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
            log.warn("Start ignored: Prodamus Predictive 2 is already running");
            return;
        }
        this.listener = listener;
        latestForecast.set(null);
        forwardedForecastVersion = 0;
        listener.onRunningChanged(true);
        listener.onStatus("Получаю два ключа Gemini…");
        executor.execute(() -> doStart(settings, promptProfileId, clientContext));
    }

    private void doStart(AppSettings settings, long promptProfileId, String clientContext) {
        try {
            validate(settings, promptProfileId);
            PredictiveSessionBundle bundle = backend.startPredictiveV2Session(promptProfileId,
                    clientContext == null ? "" : clientContext.trim());
            if (bundle == null || bundle.tactical() == null || bundle.predictive() == null) {
                throw new IllegalStateException("Backend не выдал две Gemini-сессии для Predictive 2");
            }

            recommender = new GeminiLiveClient(mapper, new RecommenderEvents());
            forecaster = new GeminiLiveClient(mapper, new ForecasterEvents());
            connectBoth(bundle);

            microphoneDetector = new UtteranceDetector(SpeakerRole.MANAGER, settings.vadThreshold(),
                    settings.silenceMillis(), this::sendManagerUtterance);
            int refinementMillis = settings.activeListeningIntervalSeconds() * 1_000;
            customerDetector = new ProgressiveUtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                    settings.silenceMillis(), FIRST_WORDS_MILLIS, refinementMillis, this::sendCustomerSegment);
            microphone = audioService.captureMicrophone(settings.microphoneDeviceId(),
                    microphoneDetector::accept, this::fail);
            loopback = audioService.captureLoopback(settings.loopbackDeviceId(),
                    customerDetector::accept, this::fail);
            microphone.start();
            loopback.start();
            listener.onStatus("Слушаю · ранняя подсказка + уточнение");
            log.info("Prodamus Predictive 2 started: firstWordsMs={}, refinementMs={}",
                    FIRST_WORDS_MILLIS, refinementMillis);
        } catch (Throwable throwable) {
            fail(unwrap(throwable));
        }
    }

    private void connectBoth(PredictiveSessionBundle bundle) throws Exception {
        listener.onStatus("Подключаю рекомендателя и прогнозиста…");
        try (ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> visible = connections.submit(() -> recommender.connect(bundle.tactical()));
            Future<?> hidden = connections.submit(() -> forecaster.connect(bundle.predictive()));
            visible.get();
            hidden.get();
        }
    }

    private void sendManagerUtterance(SpeakerRole role, byte[] audio) {
        if (!running.get() || audio == null || audio.length == 0) return;
        GeminiLiveClient visible = recommender;
        GeminiLiveClient hidden = forecaster;
        if (visible == null || hidden == null) return;
        recommenderSender.execute(() -> sendAudioIfCurrent(visible, role, audio, true));
        forecasterSender.execute(() -> sendAudioIfCurrent(hidden, role, audio, false));
    }

    private void sendCustomerSegment(SpeechSegment segment) {
        if (!running.get() || segment == null) return;
        GeminiLiveClient visible = recommender;
        GeminiLiveClient hidden = forecaster;
        if (visible == null || hidden == null) return;

        if (segment.firstSegment()) {
            AssistantListener current = listener;
            if (current != null) current.onSuggestionBoundary();
        }
        log.info("Customer progressive segment: utterance={}, index={}, final={}, bytes={}",
                segment.utteranceId(), segment.segmentIndex(), segment.finalSegment(), segment.audio().length);

        recommenderSender.execute(() -> {
            try {
                if (!isCurrent(visible, true)) return;
                if (segment.audio().length > 0) {
                    visible.sendUtterance(segment.role(), segment.audio(), takeLatestForecastContext());
                }
            } catch (Throwable throwable) {
                fail(throwable);
            }
        });
        if (segment.audio().length > 0) {
            forecasterSender.execute(() -> sendAudioIfCurrent(hidden, segment.role(), segment.audio(), false));
        }
    }

    private String takeLatestForecastContext() {
        ForecastSnapshot snapshot = latestForecast.get();
        if (snapshot == null || snapshot.version() <= forwardedForecastVersion) return "";
        forwardedForecastVersion = snapshot.version();
        log.debug("Hidden forecast forwarded to recommender: version={}, chars={}",
                snapshot.version(), snapshot.text().length());
        return "[СКРЫТЫЙ ПРОГНОЗ #" + snapshot.version() + "]\n" + snapshot.text();
    }

    private void sendAudioIfCurrent(GeminiLiveClient target, SpeakerRole role, byte[] audio, boolean visible) {
        if (!isCurrent(target, visible)) return;
        try {
            target.sendUtterance(role, audio);
        } catch (Throwable throwable) {
            fail(throwable);
        }
    }

    private boolean isCurrent(GeminiLiveClient target, boolean visible) {
        return running.get() && target != null && target == (visible ? recommender : forecaster);
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Prodamus Predictive 2 stopping");
        if (microphoneDetector != null) microphoneDetector.flush();
        if (customerDetector != null) customerDetector.flush();
        if (microphone != null) microphone.close();
        if (loopback != null) loopback.close();
        if (recommender != null) recommender.close();
        if (forecaster != null) forecaster.close();
        microphoneDetector = null;
        customerDetector = null;
        microphone = null;
        loopback = null;
        recommender = null;
        forecaster = null;
        latestForecast.set(null);

        AssistantListener current = listener;
        if (current != null) {
            current.onSuggestionBoundary();
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
        log.error("Predictive 2 assistant failure", throwable);
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
        recommenderSender.shutdownNow();
        forecasterSender.shutdownNow();
    }

    private final class RecommenderEvents implements GeminiEventListener {
        @Override
        public void onStatus(String status) {
            AssistantListener current = listener;
            if (current != null) current.onStatus(status);
        }

        @Override
        public void onSuggestion(String text, boolean complete) {
            AssistantListener current = listener;
            if (current != null) current.onSuggestion(SuggestionKind.RECOMMENDATION, text, false);
        }

        @Override
        public void onTranscript(String text) {
            AssistantListener current = listener;
            if (current != null) current.onTranscript(text);
        }

        @Override public void onError(Throwable error) { fail(error); }
    }

    private final class ForecasterEvents implements GeminiEventListener {
        @Override
        public void onStatus(String status) {
            if (status != null && status.startsWith("Переподключение")) {
                AssistantListener current = listener;
                if (current != null) current.onStatus("Восстанавливаю скрытый прогноз…");
            }
        }

        @Override
        public void onSuggestion(String text, boolean complete) {
            if (!complete || text == null || text.isBlank()) return;
            String value = text.trim();
            if (value.equals("—") || value.equals("-")) return;
            long version = forecastSequence.incrementAndGet();
            latestForecast.set(new ForecastSnapshot(version, value));
            log.debug("Hidden forecast updated: version={}, chars={}", version, value.length());
        }

        @Override public void onTranscript(String text) { }
        @Override public void onError(Throwable error) { fail(error); }
    }

    private record ForecastSnapshot(long version, String text) { }
}
