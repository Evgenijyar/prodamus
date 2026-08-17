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
import ru.prodamus.client.server.BackendClient.LiveSessionDescriptor;
import ru.prodamus.client.server.BackendClient.PredictiveSessionBundle;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AssistantCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AssistantCoordinator.class);
    private static final int FIRST_WORDS_MILLIS = 600;

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
    private final AtomicLong singleTurnSequence = new AtomicLong();
    private final AtomicReference<ForecastSnapshot> latestForecast = new AtomicReference<>();
    private final AtomicReference<RecommendationTurn> activeRecommendation = new AtomicReference<>();
    private final Object customerQueueLock = new Object();
    private final Deque<PendingCustomerTurn> pendingCustomerTurns = new ArrayDeque<>();
    private boolean customerDrainScheduled;

    private volatile long forwardedForecastVersion;
    private volatile long conversationId;
    private volatile GeminiLiveClient recommender;
    private volatile GeminiLiveClient forecaster;
    private volatile WindowsAudioService.WasapiCapture microphone;
    private volatile WindowsAudioService.WasapiCapture loopback;
    private volatile UtteranceDetector microphoneDetector;
    private volatile ProgressiveUtteranceDetector customerDetector;
    private volatile UtteranceDetector customerSimpleDetector;
    private volatile boolean dualMode;
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
        synchronized (customerQueueLock) {
            pendingCustomerTurns.clear();
            customerDrainScheduled = false;
        }
        forwardedForecastVersion = 0;
        conversationId = conversationSequence.incrementAndGet();
        listener.onRunningChanged(true);
        listener.onStatus(settings.dualSession() ? "Получаю два ключа Gemini…" : "Получаю ключ Gemini…");
        executor.execute(() -> doStart(settings, promptProfileId, clientContext));
    }

    private void doStart(AppSettings settings, long promptProfileId, String clientContext) {
        try {
            validate(settings, promptProfileId);
            dualMode = settings.dualSession();
            recommender = new GeminiLiveClient(mapper, new RecommenderEvents());
            if (dualMode) {
                PredictiveSessionBundle bundle = backend.startPredictiveV2Session(promptProfileId,
                        clientContext == null ? "" : clientContext.trim());
                if (bundle == null || bundle.tactical() == null || bundle.predictive() == null) {
                    throw new IllegalStateException("Backend не выдал две Gemini-сессии для Predictive 2");
                }
                forecaster = new GeminiLiveClient(mapper, new ForecasterEvents());
                connectBoth(bundle);
            } else {
                LiveSessionDescriptor descriptor = backend.startLiveSession(promptProfileId,
                        clientContext == null ? "" : clientContext.trim());
                recommender.connect(descriptor);
                forecaster = null;
            }

            microphoneDetector = new UtteranceDetector(SpeakerRole.MANAGER, settings.vadThreshold(),
                    settings.silenceMillis(), this::sendManagerUtterance);
            int refinementMillis = settings.activeListening() ? settings.activeListeningIntervalSeconds() * 1_000 : 0;
            if (dualMode && settings.activeListening()) {
                customerDetector = new ProgressiveUtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                        settings.silenceMillis(), FIRST_WORDS_MILLIS, refinementMillis, this::sendCustomerSegment);
                customerSimpleDetector = null;
            } else if (dualMode) {
                customerDetector = null;
                customerSimpleDetector = new UtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                        settings.silenceMillis(), this::sendCompletedCustomerUtterance);
            } else {
                customerDetector = null;
                customerSimpleDetector = new UtteranceDetector(SpeakerRole.CUSTOMER, settings.vadThreshold(),
                        settings.silenceMillis(), refinementMillis, this::sendSingleUtterance);
            }
            microphone = audioService.captureMicrophone(settings.microphoneDeviceId(),
                    microphoneDetector::accept, this::fail);
            loopback = audioService.captureLoopback(settings.loopbackDeviceId(),
                    audio -> {
                        ProgressiveUtteranceDetector progressive = customerDetector;
                        if (progressive != null) progressive.accept(audio);
                        else {
                            UtteranceDetector simple = customerSimpleDetector;
                            if (simple != null) simple.accept(audio);
                        }
                    }, this::fail);
            microphone.start();
            loopback.start();
            listener.onStatus(dualMode ? "Слушаю · прогноз + быстрая рекомендация" : "Слушаю · один AI-ключ");
            log.info("Prodamus production assistant started: mode={}, activeListening={}, firstWordsMs={}, refinementMs={}",
                    dualMode ? "PREDICTIVE_V2" : "SINGLE_COMPATIBLE", settings.activeListening(),
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
        if (!dualMode) {
            sendSingleUtterance(role, audio);
            return;
        }
        GeminiLiveClient visible = recommender;
        GeminiLiveClient hidden = forecaster;
        if (visible == null || hidden == null) return;
        long turnId = managerTurnSequence.incrementAndGet();
        String control = control(role, turnId, 0, "MANAGER_COMPLETE", audio.length, "");
        recommenderSender.execute(() -> {
            if (!isCurrent(visible, true)) return;
            drainPendingCustomersInline(visible);
            activeRecommendation.set(null);
            try {
                visible.sendUtteranceAndAwait(role, audio, control);
            } catch (Throwable throwable) {
                fail(throwable);
            }
        });
        forecasterSender.execute(() -> sendAudioIfCurrent(hidden, role, audio, control, false));
    }

    private void sendCompletedCustomerUtterance(SpeakerRole role, byte[] audio) {
        long utteranceId = singleTurnSequence.incrementAndGet();
        sendCustomerSegment(new SpeechSegment(role, utteranceId, 0, true, audio.length, audio));
    }

    private void sendSingleUtterance(SpeakerRole role, byte[] audio) {
        GeminiLiveClient target = recommender;
        if (!running.get() || target == null || audio == null || audio.length == 0) return;
        long utteranceId = (conversationId << 32) | (singleTurnSequence.incrementAndGet() & 0xffff_ffffL);
        String phase = role == SpeakerRole.CUSTOMER ? "SINGLE_CLIENT" : "SINGLE_MANAGER";
        String context = "[" + role.label().toUpperCase(java.util.Locale.ROOT) + "]\n"
                + control(role, utteranceId, 0, phase, audio.length, "");
        recommenderSender.execute(() -> {
            if (!isCurrent(target, true)) return;
            RecommendationTurn turn = role == SpeakerRole.CUSTOMER
                    ? new RecommendationTurn(utteranceId, SuggestionKind.FINAL) : null;
            activeRecommendation.set(turn);
            try {
                target.sendUtteranceAndAwait(role, audio, context);
            } catch (Throwable throwable) {
                fail(throwable);
            } finally {
                if (turn != null) activeRecommendation.compareAndSet(turn, null);
            }
        });
    }

    private void sendCustomerSegment(SpeechSegment segment) {
        if (!running.get() || segment == null) return;
        GeminiLiveClient visible = recommender;
        GeminiLiveClient hidden = forecaster;
        if (visible == null || hidden == null) return;

        long displayUtteranceId = (conversationId << 32) | (segment.utteranceId() & 0xffff_ffffL);
        String phase = segment.finalSegment() ? "CLIENT_FINAL"
                : segment.firstSegment() ? "CLIENT_EARLY" : "CLIENT_CONTINUATION";
        // A forecast may finish just after the first 600 ms fragment. Forward
        // the newest valid one on the first segment for which it is available.
        String forecast = takeLatestForecastContext();
        String forecastControl = control(segment.role(), displayUtteranceId, segment.segmentIndex(), phase,
                segment.cumulativeAudioBytes(), "");
        log.info("Customer progressive segment: utterance={}, index={}, final={}, bytes={}",
                segment.utteranceId(), segment.segmentIndex(), segment.finalSegment(), segment.audio().length);

        enqueueCustomerTurn(visible, segment, displayUtteranceId, forecast);
        forecasterSender.execute(() -> sendAudioIfCurrent(hidden, segment.role(), segment.audio(),
                forecastControl, false));
    }

    private void enqueueCustomerTurn(GeminiLiveClient target, SpeechSegment segment,
                                     long displayUtteranceId, String forecast) {
        synchronized (customerQueueLock) {
            PendingCustomerTurn last = pendingCustomerTurns.peekLast();
            if (last != null && last.displayUtteranceId() == displayUtteranceId) {
                pendingCustomerTurns.removeLast();
                pendingCustomerTurns.addLast(last.merge(segment, forecast));
                log.info("Coalesced queued customer audio: utterance={}, latestIndex={}, final={}, bytes={}",
                        segment.utteranceId(), segment.segmentIndex(), segment.finalSegment(),
                        pendingCustomerTurns.peekLast().segment().audio().length);
            } else {
                pendingCustomerTurns.addLast(new PendingCustomerTurn(
                        displayUtteranceId, segment, forecast, segment.firstSegment()));
            }
            if (!customerDrainScheduled) {
                customerDrainScheduled = true;
                recommenderSender.execute(() -> drainOneCustomer(target));
            }
        }
    }

    private void drainOneCustomer(GeminiLiveClient target) {
        PendingCustomerTurn pending;
        synchronized (customerQueueLock) {
            pending = pendingCustomerTurns.pollFirst();
        }
        if (pending != null && isCurrent(target, true)) processCustomerTurn(target, pending);
        synchronized (customerQueueLock) {
            if (!pendingCustomerTurns.isEmpty() && isCurrent(target, true)) {
                recommenderSender.execute(() -> drainOneCustomer(target));
            } else {
                customerDrainScheduled = false;
            }
        }
    }

    /** Ensures a queued client final can never be overtaken by the manager turn that follows it. */
    private void drainPendingCustomersInline(GeminiLiveClient target) {
        while (isCurrent(target, true)) {
            PendingCustomerTurn pending;
            synchronized (customerQueueLock) {
                pending = pendingCustomerTurns.pollFirst();
            }
            if (pending == null) return;
            processCustomerTurn(target, pending);
        }
    }

    private void processCustomerTurn(GeminiLiveClient target, PendingCustomerTurn pending) {
        SpeechSegment segment = pending.segment();
        SuggestionKind kind = segment.finalSegment() ? SuggestionKind.FINAL : SuggestionKind.HYPOTHESIS;
        String phase = segment.finalSegment() ? "CLIENT_FINAL"
                : pending.containsFirstSegment() ? "CLIENT_EARLY" : "CLIENT_CONTINUATION";
        String forecast = pending.forecast().isBlank() ? takeLatestForecastContext() : pending.forecast();
        String recommendationControl = control(segment.role(), pending.displayUtteranceId(), segment.segmentIndex(),
                phase, segment.cumulativeAudioBytes(), forecast);
        RecommendationTurn turn = new RecommendationTurn(pending.displayUtteranceId(), kind);
        activeRecommendation.set(turn);
        try {
            TurnResult result = target.sendUtteranceAndAwait(segment.role(), segment.audio(), recommendationControl);
            if (segment.finalSegment()) {
                ensureFinalRecommendation(target, segment, pending.displayUtteranceId(), result);
            }
        } catch (Throwable throwable) {
            fail(throwable);
        } finally {
            activeRecommendation.compareAndSet(turn, null);
        }
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
        for (int recovery = 1; recovery <= 1 && isCurrent(target, true)
                && !SuggestionQuality.isCompleteRecommendation(result.text())
                && !"protocol-error".equals(result.status())
                && segment.audio() != null && segment.audio().length > 0; recovery++) {
            log.warn("Final recommendation missing or incomplete: utterance={}, recovery={}, status={}, textChars={}",
                    segment.utteranceId(), recovery, result.status(), result.text() == null ? 0 : result.text().length());
            String recoveryControl = control(segment.role(), displayUtteranceId, segment.segmentIndex(),
                    "CLIENT_FINAL_RECOVERY", segment.cumulativeAudioBytes(), "")
                    + "\n[RECOVERY]\nПредыдущий итог отсутствовал или был оборван. "
                    + "Верни одну полную готовую фразу менеджера по уже полученной реплике клиента.\n[/RECOVERY]";
            // This is a valid non-empty audio retry. The same technical id tells
            // the model not to append the duplicated tail to conversation facts.
            result = target.sendUtteranceAndAwait(segment.role(), segment.audio(), recoveryControl);
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
        if (customerSimpleDetector != null) customerSimpleDetector.flush();
        if (microphone != null) microphone.close();
        if (loopback != null) loopback.close();
        if (recommender != null) recommender.close();
        if (forecaster != null) forecaster.close();
        microphoneDetector = null;
        customerDetector = null;
        customerSimpleDetector = null;
        microphone = null;
        loopback = null;
        recommender = null;
        forecaster = null;
        dualMode = false;
        latestForecast.set(null);
        activeRecommendation.set(null);
        synchronized (customerQueueLock) {
            pendingCustomerTurns.clear();
            customerDrainScheduled = false;
        }

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
                if (dualMode && complete && turn.kind() == SuggestionKind.FINAL
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
            String value = ForecastQuality.normalize(text);
            if (value.isBlank()) {
                if (!text.trim().equals("—") && !text.trim().equals("-")) {
                    log.warn("Discarding malformed hidden forecast: chars={}", text.length());
                }
                return;
            }
            long version = forecastSequence.incrementAndGet();
            latestForecast.set(new ForecastSnapshot(version, value));
            log.debug("Hidden forecast updated: version={}, chars={}", version, value.length());
        }

        @Override public void onTranscript(String text) { }
        @Override public void onError(Throwable error) { fail(error); }
    }

    private record ForecastSnapshot(long version, String text) { }
    private record RecommendationTurn(long utteranceId, SuggestionKind kind) { }

    static record PendingCustomerTurn(long displayUtteranceId, SpeechSegment segment, String forecast,
                                      boolean containsFirstSegment) {
        PendingCustomerTurn {
            forecast = forecast == null ? "" : forecast;
        }

        PendingCustomerTurn merge(SpeechSegment next, String newerForecast) {
            byte[] first = segment.audio() == null ? new byte[0] : segment.audio();
            byte[] second = next.audio() == null ? new byte[0] : next.audio();
            byte[] combined = java.util.Arrays.copyOf(first, first.length + second.length);
            System.arraycopy(second, 0, combined, first.length, second.length);
            SpeechSegment merged = new SpeechSegment(next.role(), next.utteranceId(), next.segmentIndex(),
                    next.finalSegment(), next.cumulativeAudioBytes(), combined);
            String selectedForecast = newerForecast == null || newerForecast.isBlank() ? forecast : newerForecast;
            return new PendingCustomerTurn(displayUtteranceId, merged, selectedForecast,
                    containsFirstSegment || next.firstSegment());
        }
    }
}
