package ru.prodamus.client.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.prodamus.client.audio.SpeakerRole;
import ru.prodamus.client.server.BackendClient.LiveSessionDescriptor;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct, production-oriented Gemini Live WebSocket client.
 * The Prodamus backend only provisions a constrained ephemeral token; audio
 * and all subsequent reconnects travel directly between Windows and Gemini.
 */
public final class GeminiLiveClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeminiLiveClient.class);
    /** Google recommends 20-40 ms PCM chunks for the Live API. */
    static final int AUDIO_CHUNK_BYTES = 1_280;
    private static final long OUTPUT_TRANSCRIPTION_GRACE_MS = 220;
    private static final long TURN_RESPONSE_TIMEOUT_MS = 12_000;
    private static final int MAX_TRANSIENT_SEND_ATTEMPTS = 2;

    private final ObjectMapper mapper;
    private final GeminiEventListener listener;
    private final HttpClient httpClient;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("gemini-direct-reconnect").daemon(true).factory());
    private final ScheduledExecutorService eventScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("gemini-live-events").daemon(true).factory());
    private final AtomicBoolean desiredOpen = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicBoolean reconnectAfterTurn = new AtomicBoolean();
    private final AtomicInteger orphanedTurnBoundaries = new AtomicInteger();
    private final AtomicLong successfulReconnects = new AtomicLong();
    private final AtomicLong turnSequence = new AtomicLong();
    private final Object sendLock = new Object();
    private final Object turnLock = new Object();
    private final Object connectionLock = new Object();
    private final Object suggestionLock = new Object();
    private final StringBuilder suggestion = new StringBuilder();

    private volatile LiveSessionDescriptor descriptor;
    private volatile WebSocket socket;
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile TurnTracker activeTurn;
    private volatile String resumptionHandle = "";
    private volatile long sentAudioBytes;
    private volatile long sentMessages;
    private volatile long receivedMessages;

    public GeminiLiveClient(ObjectMapper mapper, GeminiEventListener listener) {
        this.mapper = mapper;
        this.listener = listener;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void connect(LiveSessionDescriptor descriptor) {
        if (descriptor == null || descriptor.ephemeralToken() == null || descriptor.ephemeralToken().isBlank()) {
            throw new IllegalArgumentException("Сервер не выдал временный ключ Gemini");
        }
        this.descriptor = descriptor;
        this.resumptionHandle = "";
        orphanedTurnBoundaries.set(0);
        reconnectAfterTurn.set(false);
        desiredOpen.set(true);
        connected.set(false);
        readyLatch = new CountDownLatch(1);
        listener.onStatus("Подключение к Gemini Live…");
        try {
            openSocket("");
        } catch (RuntimeException exception) {
            desiredOpen.set(false);
            connected.set(false);
            readyLatch.countDown();
            throw exception;
        }
        log.info("Direct Gemini Live session is ready");
        listener.onStatus("Слушаю звонок");
    }

    /**
     * Sends one valid manual-VAD audio activity and waits until generation is
     * complete. A true transport loss may retry once with the same idempotency
     * metadata. Protocol failures and model timeouts are never blindly resent.
     */
    public TurnResult sendUtteranceAndAwait(SpeakerRole role, byte[] pcm, String context) {
        byte[] audio = pcm == null ? new byte[0] : pcm;
        if (audio.length == 0) return TurnResult.failed("empty-audio");
        if (!desiredOpen.get()) return TurnResult.failed("closed");

        synchronized (turnLock) {
            for (int attempt = 1; attempt <= MAX_TRANSIENT_SEND_ATTEMPTS && desiredOpen.get(); attempt++) {
                if (!awaitReady()) return TurnResult.failed("closed");
                TurnTracker tracker = new TurnTracker(turnSequence.incrementAndGet(), turnLabel(context));
                activeTurn = tracker;
                discardCurrentSuggestion();
                WebSocket target = socket;
                try {
                    sendAudioTurn(target, role, audio, context);
                    if (!tracker.completion.await(TURN_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        String partial = discardCurrentSuggestion();
                        if (tracker.finish(TurnStatus.TIMED_OUT, partial)) {
                            // A later turnComplete belongs to this timed-out turn, not
                            // to whichever request is active at that future moment.
                            orphanedTurnBoundaries.incrementAndGet();
                        }
                        log.warn("Gemini turn timed out without reconnect: turn={}, label={}, generationComplete={}, "
                                        + "outputChars={}",
                                tracker.id, tracker.label, tracker.generationComplete,
                                partial.length());
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return TurnResult.failed("interrupted");
                } catch (RuntimeException exception) {
                    if (!desiredOpen.get()) return TurnResult.failed("closed");
                    log.warn("Direct Gemini send failed: turn={}, attempt={}, reason={}",
                            tracker.id, attempt, rootMessage(exception));
                    tracker.finish(TurnStatus.CONNECTION_LOST, "");
                    connectionLost(target, "Ошибка отправки в Gemini Live", exception, FailureKind.TRANSIENT);
                } finally {
                    if (activeTurn == tracker) activeTurn = null;
                }

                logTurnMetrics(tracker, attempt);
                if (tracker.status == TurnStatus.COMPLETE) {
                    return new TurnResult(true, tracker.completedText, "complete");
                }
                if (tracker.status != TurnStatus.CONNECTION_LOST) {
                    return new TurnResult(false, tracker.completedText, tracker.status.externalName);
                }
                if (attempt < MAX_TRANSIENT_SEND_ATTEMPTS) {
                    log.info("Retrying transport-lost Gemini turn once: turn={}, label={}", tracker.id, tracker.label);
                }
            }
            return TurnResult.failed("transport-retry-exhausted");
        }
    }

    private void sendAudioTurn(WebSocket target, SpeakerRole role, byte[] pcm, String context) {
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("Manual VAD activity must contain non-empty audio");
        }
        synchronized (sendLock) {
            requireCurrentConnection(target);
            log.info("Sending Gemini audio turn: role={}, bytes={}, durationMs={}, chunkMs=40, label={}",
                    role, pcm.length, pcm.length * 1_000L / 32_000L, turnLabel(context));
            listener.onStatus("Распознана реплика: " + role.label().toLowerCase());
            sendJson(target, mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().set("activityStart", mapper.createObjectNode())));
            String control = context == null || context.isBlank()
                    ? "[CONTROL]\nspeaker=" + role.name() + "\n[/CONTROL]"
                    : context.trim();
            sendJson(target, mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().put("text", control)));
            for (int offset = 0; offset < pcm.length; offset += AUDIO_CHUNK_BYTES) {
                requireCurrentConnection(target);
                int length = Math.min(AUDIO_CHUNK_BYTES, pcm.length - offset);
                String data = Base64.getEncoder().encodeToString(
                        java.util.Arrays.copyOfRange(pcm, offset, offset + length));
                ObjectNode audio = mapper.createObjectNode()
                        .put("mimeType", "audio/pcm;rate=16000")
                        .put("data", data);
                sendJson(target, mapper.createObjectNode().set("realtimeInput",
                        mapper.createObjectNode().set("audio", audio)));
                sentAudioBytes += length;
            }
            sendJson(target, mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().set("activityEnd", mapper.createObjectNode())));
        }
    }

    private boolean awaitReady() {
        while (desiredOpen.get()) {
            if (connected.get() && socket != null) return true;
            CountDownLatch latch = readyLatch;
            try {
                latch.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void requireCurrentConnection(WebSocket target) {
        if (!desiredOpen.get() || !connected.get() || target == null || target != socket) {
            throw new IllegalStateException("Соединение Gemini изменилось во время отправки");
        }
    }

    private void openSocket(String handle) {
        synchronized (connectionLock) {
            if (!desiredOpen.get()) return;
            LiveSessionDescriptor current = descriptor;
            if (current == null) throw new IllegalStateException("Параметры Gemini Live отсутствуют");

            ConnectionListener connection = new ConnectionListener();
            URI uri = URI.create(buildUrl(current.websocketUrl(), current.ephemeralToken()));
            log.info("Opening direct Gemini WebSocket: resume={}, reconnect={}",
                    handle != null && !handle.isBlank(), reconnecting.get());
            WebSocket newSocket;
            try {
                newSocket = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(7))
                        .buildAsync(uri, connection)
                        .orTimeout(10, TimeUnit.SECONDS)
                        .join();
            } catch (CompletionException exception) {
                Throwable cause = unwrap(exception);
                if (cause instanceof WebSocketHandshakeException handshake) {
                    throw new IllegalStateException("Gemini Live отклонил подключение (HTTP "
                            + handshake.getResponse().statusCode() + ")", cause);
                }
                throw new IllegalStateException("Не удалось подключиться к Gemini Live: " + rootMessage(cause), cause);
            }

            WebSocket previous = socket;
            socket = newSocket;
            try {
                sendJson(newSocket, setupMessage(current.model(), handle));
                if (!connection.setupComplete.await(12, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Gemini Live не подтвердил настройку сессии за 12 секунд");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                safeClose(newSocket, "interrupted");
                if (previous != null) safeClose(previous, "reconnect-aborted");
                if (socket == newSocket) socket = null;
                throw new IllegalStateException("Подключение Gemini прервано", exception);
            } catch (RuntimeException exception) {
                safeClose(newSocket, "setup-failed");
                if (previous != null) safeClose(previous, "reconnect-failed");
                if (socket == newSocket) socket = null;
                throw exception;
            }

            connected.set(true);
            readyLatch.countDown();
            if (previous != null && previous != newSocket) safeClose(previous, "reconnected");
        }
    }

    private JsonNode setupMessage(String model, String handle) {
        String normalized = model != null && model.startsWith("models/") ? model : "models/" + model;
        ObjectNode generation = mapper.createObjectNode()
                .set("responseModalities", mapper.createArrayNode().add("AUDIO"));
        generation.put("temperature", 0.2);
        generation.put("maxOutputTokens", 512);
        generation.set("thinkingConfig", mapper.createObjectNode()
                .put("thinkingLevel", "minimal")
                .put("includeThoughts", false));

        ObjectNode setup = mapper.createObjectNode();
        setup.put("model", normalized);
        setup.set("generationConfig", generation);
        setup.set("inputAudioTranscription", mapper.createObjectNode());
        setup.set("outputAudioTranscription", mapper.createObjectNode());
        setup.set("realtimeInputConfig", mapper.createObjectNode()
                .set("automaticActivityDetection", mapper.createObjectNode().put("disabled", true)));
        ((ObjectNode) setup.get("realtimeInputConfig"))
                .put("activityHandling", "NO_INTERRUPTION")
                .put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY");
        setup.set("contextWindowCompression", mapper.createObjectNode()
                .set("slidingWindow", mapper.createObjectNode()));
        ObjectNode resumption = mapper.createObjectNode();
        if (handle != null && !handle.isBlank()) resumption.put("handle", handle);
        setup.set("sessionResumption", resumption);
        return mapper.createObjectNode().set("setup", setup);
    }

    private void connectionLost(WebSocket source, String reason, Throwable error, FailureKind kind) {
        if (!desiredOpen.get() || source == null || source != socket) return;
        if (!connected.getAndSet(false) && reconnecting.get()) return;
        reconnectAfterTurn.set(false);
        orphanedTurnBoundaries.set(0);
        discardCurrentSuggestion();
        finishActiveTurn(kind == FailureKind.PROTOCOL ? TurnStatus.PROTOCOL_ERROR : TurnStatus.CONNECTION_LOST, "");
        readyLatch = new CountDownLatch(1);
        if (!reconnecting.compareAndSet(false, true)) return;

        listener.onStatus(kind == FailureKind.PROTOCOL
                ? "Восстанавливаю сессию после отклонённого turn…"
                : "Переподключение к Gemini…");
        if (error == null) log.warn("Gemini connection lost: kind={}, reason={}", kind, reason);
        else log.warn("Gemini connection lost: kind={}, reason={}: {}", kind, reason, rootMessage(error));
        reconnectExecutor.execute(this::reconnectLoop);
    }

    private void reconnectLoop() {
        int attempt = 0;
        while (desiredOpen.get() && reconnecting.get()) {
            attempt++;
            try {
                openSocket(resumptionHandle);
                long count = successfulReconnects.incrementAndGet();
                reconnecting.set(false);
                listener.onStatus("Слушаю звонок");
                log.info("Gemini reconnected successfully: attempt={}, resumed={}, reconnectCount={}",
                        attempt, !resumptionHandle.isBlank(), count);
                return;
            } catch (Throwable exception) {
                connected.set(false);
                log.warn("Gemini reconnect attempt {} failed: {}", attempt, rootMessage(exception));
                if (attempt == 5) {
                    listener.onStatus("Gemini временно недоступен · продолжаю переподключение…");
                }
                if (!desiredOpen.get()) break;
                try {
                    Thread.sleep(reconnectDelayMillis(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        reconnecting.set(false);
    }

    private long reconnectDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> 200L;
            case 2 -> 400L;
            case 3 -> 800L;
            case 4 -> 1_500L;
            default -> 3_000L;
        };
    }

    private void sendJson(WebSocket target, JsonNode message) {
        if (target == null) throw new IllegalStateException("Gemini WebSocket ещё не создан");
        try {
            target.sendText(message.toString(), true).join();
            sentMessages++;
        } catch (CompletionException exception) {
            Throwable cause = unwrap(exception);
            throw new IllegalStateException("Ошибка отправки в Gemini Live: " + rootMessage(cause), cause);
        }
    }

    private void handleMessage(String json, WebSocket source, ConnectionListener connection) {
        if (source != socket) return;
        try {
            JsonNode root = mapper.readTree(json);
            receivedMessages++;
            if (root.has("setupComplete")) {
                connection.setupComplete.countDown();
                return;
            }
            if (root.has("error")) {
                JsonNode error = root.path("error");
                String status = error.path("status").asText("");
                FailureKind kind = isProtocolStatus(status) ? FailureKind.PROTOCOL : FailureKind.TRANSIENT;
                connectionLost(source, "Ошибка Gemini Live: " + error, null, kind);
                return;
            }

            JsonNode resumption = root.path("sessionResumptionUpdate");
            if (!resumption.isMissingNode() && resumption.path("resumable").asBoolean(false)) {
                String handle = resumption.path("newHandle").asText("");
                if (handle.isBlank()) handle = resumption.path("token").asText("");
                if (!handle.isBlank()) {
                    resumptionHandle = handle;
                    log.debug("Gemini resumption handle updated: chars={}", handle.length());
                }
            }

            JsonNode goAway = root.path("goAway");
            if (!goAway.isMissingNode()) {
                String timeLeft = goAway.path("timeLeft").asText("");
                TurnTracker tracker = activeTurn;
                if (tracker != null && tracker.status == TurnStatus.WAITING) {
                    reconnectAfterTurn.set(true);
                    log.info("Gemini GoAway deferred until active turn completes: turn={}, timeLeft={}",
                            tracker.id, timeLeft);
                } else {
                    connectionLost(source, "Gemini GoAway, timeLeft=" + timeLeft, null, FailureKind.TRANSIENT);
                }
                return;
            }

            JsonNode content = root.path("serverContent");
            if (content.isMissingNode()) return;

            boolean interrupted = content.path("interrupted").asBoolean(false);
            boolean turnComplete = content.path("turnComplete").asBoolean(false);
            // generationComplete-based logical completion may intentionally let
            // Gemini's audio-playback boundary arrive later. Consume that event
            // here so it can never complete or interrupt the next application turn.
            if (orphanedTurnBoundaries.get() > 0) {
                if (interrupted || turnComplete) {
                    int remaining = orphanedTurnBoundaries.updateAndGet(value -> Math.max(0, value - 1));
                    log.debug("Ignored late Gemini boundary from a logically completed turn: remaining={}", remaining);
                }
                return;
            }

            String input = content.path("inputTranscription").path("text").asText("");
            if (!input.isBlank()) listener.onTranscript(input);

            String output = extractOutputText(content);
            if (!output.isBlank()) {
                String current;
                synchronized (suggestionLock) {
                    mergeTranscript(suggestion, output);
                    current = suggestion.toString().trim();
                }
                TurnTracker tracker = activeTurn;
                if (tracker != null) tracker.markFirstOutput();
                listener.onSuggestion(current, false);
            }

            TurnTracker tracker = activeTurn;
            if (interrupted) {
                discardCurrentSuggestion();
                if (tracker != null) tracker.finish(TurnStatus.INTERRUPTED, "");
            }

            boolean generationComplete = content.path("generationComplete").asBoolean(false);
            if (tracker != null && tracker.status == TurnStatus.WAITING) {
                if (generationComplete) tracker.generationComplete = true;
                if (turnComplete) tracker.turnComplete = true;
                if (generationComplete || turnComplete) scheduleTurnCompletion(tracker);
            }
        } catch (Throwable exception) {
            log.error("Failed to process Gemini Live message", exception);
            connectionLost(source, "Некорректный ответ Gemini Live", exception, FailureKind.PROTOCOL);
        }
    }

    private String extractOutputText(JsonNode content) {
        String transcription = content.path("outputTranscription").path("text").asText("");
        if (!transcription.isBlank()) return transcription;
        StringBuilder text = new StringBuilder();
        JsonNode parts = content.path("modelTurn").path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.path("thought").asBoolean(false)) continue;
                String value = part.path("text").asText("");
                if (!value.isBlank()) text.append(value);
            }
        }
        return text.toString();
    }

    private void scheduleTurnCompletion(TurnTracker tracker) {
        try {
            eventScheduler.schedule(() -> completeTurnAfterTranscription(tracker),
                    OUTPUT_TRANSCRIPTION_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            completeTurnAfterTranscription(tracker);
        }
    }

    private void completeTurnAfterTranscription(TurnTracker tracker) {
        if (tracker == null || tracker != activeTurn || tracker.status != TurnStatus.WAITING) return;
        String completed = freezeCurrentSuggestion();
        boolean realBoundarySeen = tracker.turnComplete;
        if (tracker.finish(TurnStatus.COMPLETE, completed) && !realBoundarySeen) {
            orphanedTurnBoundaries.incrementAndGet();
        }
        listener.onStatus("Слушаю звонок");
        if (reconnectAfterTurn.compareAndSet(true, false)) {
            WebSocket active = socket;
            connectionLost(active, "Отложенный Gemini GoAway", null, FailureKind.TRANSIENT);
        }
    }

    private String freezeCurrentSuggestion() {
        String complete;
        synchronized (suggestionLock) {
            complete = suggestion.toString().trim();
            suggestion.setLength(0);
        }
        if (!complete.isBlank() && !complete.equals("—") && !complete.equals("-")) {
            listener.onSuggestion(complete, true);
        }
        return complete;
    }

    private String discardCurrentSuggestion() {
        synchronized (suggestionLock) {
            String discarded = suggestion.toString().trim();
            suggestion.setLength(0);
            return discarded;
        }
    }

    private void finishActiveTurn(TurnStatus status, String completedText) {
        TurnTracker tracker = activeTurn;
        if (tracker != null) tracker.finish(status, completedText);
    }

    private void logTurnMetrics(TurnTracker tracker, int attempt) {
        long completedAt = tracker.completedNanos == 0 ? System.nanoTime() : tracker.completedNanos;
        long totalMs = TimeUnit.NANOSECONDS.toMillis(completedAt - tracker.startedNanos);
        long firstOutputMs = tracker.firstOutputNanos == 0 ? -1
                : TimeUnit.NANOSECONDS.toMillis(tracker.firstOutputNanos - tracker.startedNanos);
        log.info("Gemini turn result: turn={}, label={}, status={}, attempt={}, firstOutputMs={}, totalMs={}, "
                        + "generationComplete={}, turnComplete={}, chars={}",
                tracker.id, tracker.label, tracker.status, attempt, firstOutputMs, totalMs,
                tracker.generationComplete, tracker.turnComplete, tracker.completedText.length());
    }

    @Override
    public void close() {
        boolean wasConnected = connected.getAndSet(false);
        desiredOpen.set(false);
        reconnecting.set(false);
        reconnectAfterTurn.set(false);
        orphanedTurnBoundaries.set(0);
        readyLatch.countDown();
        finishActiveTurn(TurnStatus.CLOSED, "");
        reconnectExecutor.shutdownNow();
        eventScheduler.shutdownNow();
        WebSocket active = socket;
        socket = null;
        if (active != null) safeClose(active, "stop");
        log.info("Gemini Live closed: wasConnected={}, reconnects={}, sentMessages={}, receivedMessages={}, audioBytes={}",
                wasConnected, successfulReconnects.get(), sentMessages, receivedMessages, sentAudioBytes);
    }

    public boolean isConnected() {
        return connected.get();
    }

    long successfulReconnects() {
        return successfulReconnects.get();
    }

    boolean hasResumptionHandle() {
        return !resumptionHandle.isBlank();
    }

    static CloseDisposition classifyClose(int statusCode) {
        return switch (statusCode) {
            case 1002, 1003, 1007, 1008, 1009, 1010 -> CloseDisposition.PROTOCOL;
            default -> CloseDisposition.TRANSIENT;
        };
    }

    static String mergeTranscript(String current, String chunk) {
        StringBuilder value = new StringBuilder(current == null ? "" : current);
        mergeTranscript(value, chunk);
        return value.toString();
    }

    private static void mergeTranscript(StringBuilder target, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        String current = target.toString();
        if (current.equals(chunk) || current.endsWith(chunk)) return;
        if (chunk.startsWith(current) && chunk.length() > current.length()) {
            target.setLength(0);
            target.append(chunk);
            return;
        }
        int max = Math.min(current.length(), chunk.length());
        int overlap = 0;
        for (int length = max; length > 0; length--) {
            if (current.regionMatches(current.length() - length, chunk, 0, length)) {
                overlap = length;
                break;
            }
        }
        target.append(chunk, overlap, chunk.length());
    }

    private boolean isProtocolStatus(String status) {
        return "INVALID_ARGUMENT".equalsIgnoreCase(status)
                || "FAILED_PRECONDITION".equalsIgnoreCase(status)
                || "OUT_OF_RANGE".equalsIgnoreCase(status);
    }

    private String turnLabel(String context) {
        if (context == null || context.isBlank()) return "unlabeled";
        for (String line : context.split("\\R")) {
            if (line.startsWith("phase=")) return line.substring("phase=".length()).trim();
        }
        return context.length() <= 32 ? context.replaceAll("\\s+", " ") : "custom";
    }

    private void safeClose(WebSocket webSocket, String reason) {
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason);
        } catch (RuntimeException ignored) {
        }
    }

    private String buildUrl(String endpoint, String token) {
        String base = endpoint == null || endpoint.isBlank()
                ? "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
                : endpoint.trim();
        return base + (base.contains("?") ? "&" : "?") + "access_token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record TurnResult(boolean completed, String text, String status) {
        public static TurnResult failed(String status) {
            return new TurnResult(false, "", status);
        }

        public boolean hasSuggestion() {
            return completed && text != null && !text.isBlank() && !text.trim().equals("—")
                    && !text.trim().equals("-");
        }
    }

    enum CloseDisposition { TRANSIENT, PROTOCOL }

    private enum FailureKind { TRANSIENT, PROTOCOL }

    private enum TurnStatus {
        WAITING("waiting"), COMPLETE("complete"), CONNECTION_LOST("connection-lost"),
        PROTOCOL_ERROR("protocol-error"), TIMED_OUT("timeout"), INTERRUPTED("interrupted"), CLOSED("closed");

        private final String externalName;

        TurnStatus(String externalName) {
            this.externalName = externalName;
        }
    }

    private static final class TurnTracker {
        private final long id;
        private final String label;
        private final long startedNanos = System.nanoTime();
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile TurnStatus status = TurnStatus.WAITING;
        private volatile String completedText = "";
        private volatile boolean generationComplete;
        private volatile boolean turnComplete;
        private volatile long firstOutputNanos;
        private volatile long completedNanos;

        private TurnTracker(long id, String label) {
            this.id = id;
            this.label = label;
        }

        private void markFirstOutput() {
            if (firstOutputNanos == 0) firstOutputNanos = System.nanoTime();
        }

        private synchronized boolean finish(TurnStatus value, String text) {
            if (status != TurnStatus.WAITING) return false;
            status = value;
            completedText = text == null ? "" : text;
            completedNanos = System.nanoTime();
            completion.countDown();
            return true;
        }
    }

    private final class ConnectionListener implements WebSocket.Listener {
        private final CountDownLatch setupComplete = new CountDownLatch(1);
        private final StringBuilder responseBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryResponseBuffer = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            responseBuffer.append(data);
            if (last) {
                String message = responseBuffer.toString();
                responseBuffer.setLength(0);
                handleMessage(message, webSocket, this);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryResponseBuffer.writeBytes(chunk);
            if (last) {
                byte[] message = binaryResponseBuffer.toByteArray();
                binaryResponseBuffer.reset();
                handleMessage(new String(message, StandardCharsets.UTF_8), webSocket, this);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            CloseDisposition disposition = classifyClose(statusCode);
            log.info("Gemini WebSocket closed: statusCode={}, disposition={}, reason={}",
                    statusCode, disposition, reason);
            connectionLost(webSocket, "WebSocket закрыт: " + statusCode + " " + reason, null,
                    disposition == CloseDisposition.PROTOCOL ? FailureKind.PROTOCOL : FailureKind.TRANSIENT);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Gemini WebSocket error: {}", rootMessage(error));
            connectionLost(webSocket, "Ошибка WebSocket", error, FailureKind.TRANSIENT);
        }
    }
}
