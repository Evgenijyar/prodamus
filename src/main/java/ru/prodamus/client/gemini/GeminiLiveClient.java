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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Прямой клиент Gemini Live. Backend выдаёт ephemeral token перед стартом, после
 * чего аудио и reconnect идут напрямую между Windows-клиентом и Gemini.
 */
public final class GeminiLiveClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeminiLiveClient.class);
    private static final int AUDIO_CHUNK_BYTES = 3_200;

    private final ObjectMapper mapper;
    private final GeminiEventListener listener;
    private final HttpClient httpClient;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("gemini-direct-reconnect").daemon(true).factory());
    private final AtomicBoolean desiredOpen = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicLong successfulReconnects = new AtomicLong();
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

    public void sendUtterance(SpeakerRole role, byte[] pcm) {
        sendUtterance(role, pcm, "");
    }

    public void sendUtterance(SpeakerRole role, byte[] pcm, String context) {
        if (pcm == null || pcm.length == 0 || !desiredOpen.get()) return;
        while (desiredOpen.get()) {
            if (!awaitReady()) return;
            WebSocket target = socket;
            try {
                sendUtterance(target, role, pcm, context);
                return;
            } catch (RuntimeException exception) {
                if (!desiredOpen.get()) return;
                log.warn("Direct Gemini send failed; reconnecting immediately: {}", rootMessage(exception));
                connectionLost(target, "Ошибка отправки в Gemini Live", exception);
            }
        }
    }

    public TurnResult sendUtteranceAndAwait(SpeakerRole role, byte[] pcm, String context) {
        byte[] audio = pcm == null ? new byte[0] : pcm;
        if ((audio.length == 0 && (context == null || context.isBlank())) || !desiredOpen.get()) {
            return TurnResult.failed("empty-or-closed");
        }
        synchronized (turnLock) {
            for (int attempt = 1; attempt <= 3 && desiredOpen.get(); attempt++) {
                if (!awaitReady()) return TurnResult.failed("closed");
                TurnTracker tracker = new TurnTracker();
                activeTurn = tracker;
                discardCurrentSuggestion();
                WebSocket target = socket;
                try {
                    sendUtterance(target, role, audio, context);
                    if (!tracker.completion.await(20, TimeUnit.SECONDS)) {
                        log.warn("Gemini turn did not reach turnComplete within 20 seconds; reconnecting (attempt={})",
                                attempt);
                        tracker.finish(TurnStatus.RETRY);
                        connectionLost(target, "Таймаут ожидания turnComplete", null);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return TurnResult.failed("interrupted");
                } catch (RuntimeException exception) {
                    if (!desiredOpen.get()) return TurnResult.failed("closed");
                    log.warn("Direct Gemini turn failed; reconnecting (attempt={}): {}",
                            attempt, rootMessage(exception));
                    connectionLost(target, "Ошибка отправки в Gemini Live", exception);
                    tracker.finish(TurnStatus.RETRY);
                } finally {
                    if (activeTurn == tracker) activeTurn = null;
                }
                if (tracker.status == TurnStatus.COMPLETE) {
                    return new TurnResult(true, tracker.completedText, "complete");
                }
                log.info("Retrying Gemini turn after status={}, attempt={}", tracker.status, attempt);
            }
            return TurnResult.failed("retries-exhausted");
        }
    }

    private void sendUtterance(WebSocket target, SpeakerRole role, byte[] pcm, String context) {
        synchronized (sendLock) {
            requireCurrentConnection(target);
            log.info("Sending complete utterance directly to Gemini: role={}, bytes={}, durationMs={}",
                    role, pcm.length, pcm.length * 1000L / 32_000L);
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
        ObjectNode setup = mapper.createObjectNode();
        setup.put("model", normalized);
        setup.set("generationConfig", mapper.createObjectNode()
                .set("responseModalities", mapper.createArrayNode().add("AUDIO")));
        setup.set("inputAudioTranscription", mapper.createObjectNode());
        setup.set("outputAudioTranscription", mapper.createObjectNode());
        setup.set("realtimeInputConfig", mapper.createObjectNode()
                .set("automaticActivityDetection", mapper.createObjectNode().put("disabled", true)));
        setup.set("contextWindowCompression", mapper.createObjectNode()
                .set("slidingWindow", mapper.createObjectNode()));
        ObjectNode resumption = mapper.createObjectNode();
        if (handle != null && !handle.isBlank()) resumption.put("handle", handle);
        setup.set("sessionResumption", resumption);
        return mapper.createObjectNode().set("setup", setup);
    }

    private void connectionLost(WebSocket source, String reason, Throwable error) {
        if (!desiredOpen.get() || source == null || source != socket) return;
        if (!connected.getAndSet(false) && reconnecting.get()) return;
        discardCurrentSuggestion();
        finishActiveTurn(TurnStatus.RETRY, "");
        readyLatch = new CountDownLatch(1);
        if (!reconnecting.compareAndSet(false, true)) return;

        listener.onStatus("Переподключение к Gemini…");
        if (error == null) log.warn("Gemini connection lost: {}", reason);
        else log.warn("Gemini connection lost: {}: {}", reason, rootMessage(error));
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
                connectionLost(source, "Ошибка Gemini Live: " + root.path("error"), null);
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
                connectionLost(source, "Gemini GoAway, timeLeft=" + goAway.path("timeLeft").asText(""), null);
                return;
            }

            JsonNode content = root.path("serverContent");
            if (content.isMissingNode()) return;
            String input = content.path("inputTranscription").path("text").asText("");
            if (!input.isBlank()) listener.onTranscript(input);

            String output = content.path("outputTranscription").path("text").asText("");
            if (!output.isBlank()) {
                String current;
                synchronized (suggestionLock) {
                    suggestion.append(output);
                    current = suggestion.toString().trim();
                }
                listener.onSuggestion(current, false);
            }
            if (content.path("interrupted").asBoolean(false)) {
                discardCurrentSuggestion();
                TurnTracker tracker = activeTurn;
                if (tracker != null) tracker.interrupted = true;
            }
            if (content.path("turnComplete").asBoolean(false)) {
                TurnTracker tracker = activeTurn;
                // outputTranscription is an independent stream and may arrive just after turnComplete.
                // A short grace period keeps the next turn serialized without losing the final words.
                try {
                    reconnectExecutor.schedule(() -> completeTurnAfterTranscription(tracker),
                            180, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {
                    completeTurnAfterTranscription(tracker);
                }
            }
        } catch (Throwable exception) {
            log.error("Failed to process Gemini Live message", exception);
            connectionLost(source, "Некорректный ответ Gemini Live", exception);
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

    private void completeTurnAfterTranscription(TurnTracker tracker) {
        if (tracker == null || tracker != activeTurn || tracker.status != TurnStatus.WAITING) return;
        String completed = tracker.interrupted ? discardCurrentSuggestion() : freezeCurrentSuggestion();
        tracker.finish(tracker.interrupted ? TurnStatus.RETRY : TurnStatus.COMPLETE, completed);
        listener.onStatus("Слушаю звонок");
    }

    @Override
    public void close() {
        boolean wasConnected = connected.getAndSet(false);
        desiredOpen.set(false);
        reconnecting.set(false);
        readyLatch.countDown();
        finishActiveTurn(TurnStatus.CLOSED, "");
        reconnectExecutor.shutdownNow();
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

    public record TurnResult(boolean completed, String text, String status) {
        public static TurnResult failed(String status) { return new TurnResult(false, "", status); }

        public boolean hasSuggestion() {
            return completed && text != null && !text.isBlank() && !text.trim().equals("—")
                    && !text.trim().equals("-");
        }
    }

    private enum TurnStatus { WAITING, COMPLETE, RETRY, CLOSED }

    private static final class TurnTracker {
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile TurnStatus status = TurnStatus.WAITING;
        private volatile String completedText = "";
        private volatile boolean interrupted;

        private void finish(TurnStatus value) { finish(value, ""); }

        private synchronized void finish(TurnStatus value, String text) {
            if (status != TurnStatus.WAITING) return;
            status = value;
            completedText = text == null ? "" : text;
            completion.countDown();
        }
    }

    private void safeClose(WebSocket webSocket, String reason) {
        try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason); }
        catch (RuntimeException ignored) { }
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

    private final class ConnectionListener implements WebSocket.Listener {
        private final CountDownLatch setupComplete = new CountDownLatch(1);
        private final StringBuilder responseBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryResponseBuffer = new ByteArrayOutputStream();

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

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
            log.info("Gemini WebSocket closed: statusCode={}, reason={}", statusCode, reason);
            connectionLost(webSocket, "WebSocket закрыт: " + statusCode + " " + reason, null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Gemini WebSocket error: {}", rootMessage(error));
            connectionLost(webSocket, "Ошибка WebSocket", error);
        }
    }
}
