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
import ru.prodamus.client.gemini.GeminiLiveClient.TurnResult;
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
    private final AtomicLong conversationSequence = new AtomicLong();
    private final AtomicLong managerTurnSequence = new AtomicLong();
    private final AtomicReference<ForecastSnapshot> latestForecast = new AtomicReference<>();
    private final AtomicReference<RecommendationTurn> activeRecommendation = new AtomicReference<>();

    private volatile long forwardedForecastVersion;
    private volatile long conversationId;
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
        activeRecommendation.set(null);
        forwardedForecastVersion = 0;
        conversationId = conversationSequence.incrementAndGet();
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
        long turnId = managerTurnSequence.incrementAndGet();
        String control = control(role, turnId, 0, "MANAGER_COMPLETE", audio.length, "");
        recommenderSender.execute(() -> {
            if (!isCurrent(visible, true)) return;
            activeRecommendation.set(null);
            try {
                visible.sendUtteranceAndAwait(role, audio, control);
            } catch (Throwable throwable) {
                fail(throwable);
            }
        });
        forecasterSender.execute(() -> sendAudioIfCurrent(hidden, role, audio, control, false));
    }

    private void sendCustomerSegment(SpeechSegment segment) {
        if (!running.get() || segment == null) return;
        GeminiLiveClient visible = recommender;
        GeminiLiveClient hidden = forecaster;
        if (visible == null || hidden == null) return;

        long displayUtteranceId = (conversationId << 32) | (segment.utteranceId() & 0xffff_ffffL);
        SuggestionKind kind = segment.finalSegment() ? SuggestionKind.FINAL : SuggestionKind.HYPOTHESIS;
        String phase = segment.finalSegment() ? "CLIENT_FINAL"
                : segment.firstSegment() ? "CLIENT_EARLY" : "CLIENT_CONTINUATION";
        String forecast = segment.firstSegment() ? takeLatestForecastContext() : "";
        String recommendationControl = control(segment.role(), displayUtteranceId, segment.segmentIndex(), phase,
                segment.cumulativeAudioBytes(), forecast);
        String forecastControl = control(segment.role(), displayUtteranceId, segment.segmentIndex(), phase,
                segment.cumulativeAudioBytes(), "");
        log.info("Customer progressive segment: utterance={}, index={}, final={}, bytes={}",
                segment.utteranceId(), segment.segmentIndex(), segment.finalSegment(), segment.audio().length);

        recommenderSender.execute(() -> {
            try {
                if (!isCurrent(visible, true)) return;
                activeRecommendation.set(new RecommendationTurn(displayUtteranceId, kind));
                TurnResult result = visible.sendUtteranceAndAwait(segment.role(), segment.audio(),
                        recommendationControl);
                if (segment.finalSegment()) ensureFinalRecommendation(visible, segment, displayUtteranceId, result);
            } catch (Throwable throwable) {
                fail(throwable);
            } finally {
                activeRecommendation.set(null);
            }
        });
        forecasterSender.execute(() -> sendAudioIfCurrent(hidden, segment.role(), segment.audio(),
                forecastControl, false));
    }

    private String control(SpeakerRole role, long utteranceId, int segmentIndex, String phase,
                           long cumulativeAudioBytes, String forecast) {
        long cumulativeMillis = cumulativeAudioBytes * 1_000L / 32_000L;
        StringBuilder value = new StringBuilder(256)
                .append("[CONTROL]\n")
                .append("speaker=").append(role.name()).append('\n')
                .append("utterance_id=").append(utteranceId).append('\n')
                .append("segment_index=").append(segmentIndex).append('\n')
                .append("phase=").append(phase).append('\n')
                .append("cumulative_audio_ms=").append(cumulativeMillis).append('\n')
                .append("[/CONTROL]");
        if (forecast != null && !forecast.isBlank()) value.append('\n').append(forecast);
        return value.toString();
    }

    private void ensureFinalRecommendation(GeminiLiveClient target, SpeechSegment segment,
                                           long displayUtteranceId, TurnResult initial) {
        TurnResult result = initial;
        for (int recovery = 1; recovery <= 2 && isCurrent(target, true)
                && !SuggestionQuality.isCompleteRecommendation(result.text()); recovery++) {
            log.warn("Final recommendation missing or incomplete: utterance={}, recovery={}, status={}, textChars={}",
                    segment.utteranceId(), recovery, result.status(), result.text() == null ? 0 : result.text().length());
            String recoveryControl = control(segment.role(), displayUtteranceId, segment.segmentIndex(),
                    "CLIENT_FINAL_RECOVERY", segment.cumulativeAudioBytes(), "")
                    + "\n[RECOVERY]\nПредыдущий итог отсутствовал или был оборван. "
                    + "Верни одну полную готовую фразу менеджера по уже полученной реплике клиента.\n[/RECOVERY]";
            result = target.sendUtteranceAndAwait(segment.role(), new byte[0], recoveryControl);
        }
        if (!SuggestionQuality.isCompleteRecommendation(result.text())) {
            AssistantListener current = listener;
            if (current != null) {
                String fallback = "Уточните, пожалуйста, правильно ли я понял вашу основную мысль?";
                current.onSuggestion(displayUtteranceId, SuggestionKind.FINAL, fallback, true);
                log.error("Gemini did not produce a valid final recommendation after recovery: utterance={}",
                        segment.utteranceId());
            }
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

    private void sendAudioIfCurrent(GeminiLiveClient target, SpeakerRole role, byte[] audio,
                                    String context, boolean visible) {
        if (!isCurrent(target, visible)) return;
        try {
            target.sendUtteranceAndAwait(role, audio, context);
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
        activeRecommendation.set(null);

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
            RecommendationTurn turn = activeRecommendation.get();
            if (current != null && turn != null) {
                if (complete && turn.kind() == SuggestionKind.FINAL
                        && !SuggestionQuality.isCompleteRecommendation(text)) {
                    log.warn("Discarding incomplete final stream before recovery: utterance={}, chars={}",
                            turn.utteranceId(), text == null ? 0 : text.length());
                    return;
                }
                current.onSuggestion(turn.utteranceId(), turn.kind(), text, complete);
            }
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
    private record RecommendationTurn(long utteranceId, SuggestionKind kind) { }
}
