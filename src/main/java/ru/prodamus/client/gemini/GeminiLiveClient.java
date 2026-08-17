package ru.prodamus.client.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GeminiLiveClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeminiLiveClient.class);
    private static final int AUDIO_CHUNK_BYTES = 3_200;
    private static final int RESUME_ATTEMPTS = 3;
    private static final int FRESH_RECOVERY_ATTEMPTS = 3;
    private static final long TURN_COMPLETE_TIMEOUT_SECONDS = 12;
    private static final long TRANSCRIPTION_SETTLE_MILLIS = 300;

    private final ObjectMapper mapper;
    private final GeminiEventListener listener;
    private final RecoverySupport recoverySupport;
    private final HttpClient httpClient;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("gemini-reconnect").daemon(true).factory());
    private final Object sendLock = new Object();
    private final Object connectionLock = new Object();
    private final Object turnStateLock = new Object();
    private final AtomicBoolean desiredOpen = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final StringBuilder suggestion = new StringBuilder();
    private final StringBuilder inputTranscript = new StringBuilder();

    private volatile WebSocket socket;
    private volatile LiveSessionDescriptor descriptor;
    private volatile boolean ready;
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile String resumptionHandle = "";
    private volatile SpeakerRole activeTurnRole;
    private volatile long activeTurnSerial;
    private volatile boolean inputOpen;
    private volatile boolean finalizationScheduled;
    private volatile long sentAudioBytes;
    private volatile long sentMessages;
    private volatile long receivedMessages;

    public GeminiLiveClient(ObjectMapper mapper, GeminiEventListener listener, RecoverySupport recoverySupport) {
        this.mapper = mapper;
        this.listener = listener;
        this.recoverySupport = recoverySupport;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public void connect(LiveSessionDescriptor descriptor) {
        if (descriptor == null || descriptor.ephemeralToken() == null || descriptor.ephemeralToken().isBlank()) {
            throw new IllegalArgumentException("Сервер не выдал ephemeral token Gemini");
        }
        this.descriptor = descriptor;
        this.resumptionHandle = "";
        desiredOpen.set(true);
        ready = false;
        readyLatch = new CountDownLatch(1);
        listener.onStatus("Подключение к Gemini Live…");
        openSocket("", List.of());
        listener.onStatus("Слушаю звонок");
    }

    public void rotateDescriptor(LiveSessionDescriptor descriptor) {
        if (descriptor == null) return;
        this.descriptor = descriptor;
        if (desiredOpen.get()) {
            scheduleReconnect("Обновляю временный доступ Gemini");
        }
    }

    public void sendUtterance(SpeakerRole role, byte[] pcm) {
        if (!desiredOpen.get() || pcm == null || pcm.length == 0) return;
        beginUtterance(role, new byte[0]);
        for (int offset = 0; offset < pcm.length; offset += AUDIO_CHUNK_BYTES) {
            int length = Math.min(AUDIO_CHUNK_BYTES, pcm.length - offset);
            sendAudioChunk(role, java.util.Arrays.copyOfRange(pcm, offset, offset + length));
        }
        endUtterance(role);
    }

    public void beginUtterance(SpeakerRole role, byte[] initialPcm) {
        if (!desiredOpen.get() || role == null) return;
        awaitReady();

        long previousTurn;
        boolean previousInputOpen;
        synchronized (turnStateLock) {
            previousTurn = activeTurnRole == null ? -1 : activeTurnSerial;
            previousInputOpen = inputOpen;
            if (previousInputOpen) inputOpen = false;
        }
        if (previousTurn >= 0) {
            // A real conversation may continue before the previous audio response has
            // completely finished. Do not make live capture wait behind that response.
            if (previousInputOpen) {
                synchronized (sendLock) {
                    sendJson(mapper.createObjectNode().set("realtimeInput",
                            mapper.createObjectNode().set("activityEnd", mapper.createObjectNode())));
                }
            }
            finalizeTurn(previousTurn, true);
        }

        final long turnSerial;
        synchronized (turnStateLock) {
            activeTurnRole = role;
            inputTranscript.setLength(0);
            suggestion.setLength(0);
            inputOpen = true;
            finalizationScheduled = false;
            activeTurnSerial++;
            turnSerial = activeTurnSerial;
        }

        synchronized (sendLock) {
            if (!desiredOpen.get()) {
                releaseTurn(turnSerial);
                return;
            }
            if (role == SpeakerRole.CUSTOMER) {
                listener.onStatus("Реплика клиента");
            } else {
                listener.onStatus("Слушаю менеджера");
            }

            sendJson(mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().set("activityStart", mapper.createObjectNode())));

            // Служебная метка нужна модели для различения двух локальных аудиоисточников.
            // В интерфейс она никогда не попадает.
            sendJson(mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().put("text", "[" + role.label() + "]")));
            sendAudioBytes(initialPcm);
            log.debug("Live utterance started: role={}, initialBytes={}, turn={}",
                    role, initialPcm == null ? 0 : initialPcm.length, turnSerial);
        }
    }

    public void sendAudioChunk(SpeakerRole role, byte[] pcm) {
        if (!desiredOpen.get() || pcm == null || pcm.length == 0) return;
        synchronized (sendLock) {
            synchronized (turnStateLock) {
                if (activeTurnRole != role || !inputOpen) return;
            }
            sendAudioBytes(pcm);
        }
    }

    public void endUtterance(SpeakerRole role) {
        if (!desiredOpen.get() || role == null) return;
        final long turnSerial;
        synchronized (sendLock) {
            synchronized (turnStateLock) {
                if (activeTurnRole != role || !inputOpen) return;
                inputOpen = false;
                turnSerial = activeTurnSerial;
            }
            sendJson(mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().set("activityEnd", mapper.createObjectNode())));
        }
        reconnectExecutor.schedule(() -> {
            synchronized (turnStateLock) {
                if (turnSerial != activeTurnSerial || activeTurnRole == null) return;
            }
            log.warn("Gemini turn {} did not complete within {}s", turnSerial, TURN_COMPLETE_TIMEOUT_SECONDS);
            finalizeTurn(turnSerial, true);
        }, TURN_COMPLETE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        log.debug("Live utterance ended: role={}, totalAudioBytes={}, turn={}", role, sentAudioBytes, turnSerial);
    }

    private void sendAudioBytes(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        for (int offset = 0; offset < pcm.length; offset += AUDIO_CHUNK_BYTES) {
            int length = Math.min(AUDIO_CHUNK_BYTES, pcm.length - offset);
            String data = Base64.getEncoder().encodeToString(
                    java.util.Arrays.copyOfRange(pcm, offset, offset + length));
            ObjectNode audio = mapper.createObjectNode()
                    .put("mimeType", "audio/pcm;rate=16000")
                    .put("data", data);
            sendJson(mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().set("audio", audio)));
            sentAudioBytes += length;
        }
    }

    private void awaitReady() {
        if (ready) return;
        CountDownLatch latch = readyLatch;
        try {
            if (!latch.await(45, TimeUnit.SECONDS) || !ready) {
                throw new IllegalStateException("Gemini Live не удалось автоматически восстановить за 45 секунд");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание Gemini Live прервано", ex);
        }
    }

    private void openSocket(String handle, List<HistoryTurn> initialHistory) {
        synchronized (connectionLock) {
            if (!desiredOpen.get()) return;
            LiveSessionDescriptor current = descriptor;
            if (current == null) throw new IllegalStateException("Нет параметров Gemini Live сессии");
            if (current.tokenExpiresAt() != null && current.tokenExpiresAt().isBefore(Instant.now().plusSeconds(20))) {
                throw new IllegalStateException("Ephemeral token Gemini истёк");
            }

            boolean replayHistory = (handle == null || handle.isBlank()) && initialHistory != null && !initialHistory.isEmpty();
            ConnectionListener wsListener = new ConnectionListener();
            URI uri = URI.create(buildUrl(current.websocketUrl(), current.ephemeralToken()));
            log.info("Opening Gemini Live WebSocket: resume={}, replayTurns={}",
                    handle != null && !handle.isBlank(), replayHistory ? initialHistory.size() : 0);

            WebSocket newSocket;
            try {
                newSocket = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .buildAsync(uri, wsListener)
                        .orTimeout(25, TimeUnit.SECONDS)
                        .join();
            } catch (CompletionException ex) {
                Throwable cause = unwrap(ex);
                if (cause instanceof WebSocketHandshakeException handshake) {
                    throw new IllegalStateException("Gemini Live отклонил подключение (HTTP "
                            + handshake.getResponse().statusCode() + ")", cause);
                }
                throw new IllegalStateException("Не удалось подключиться к Gemini Live: " + rootMessage(cause), cause);
            }

            WebSocket previous = socket;
            socket = newSocket;
            try {
                sendJson(setupMessage(current.model(), handle, replayHistory));
                if (!wsListener.setupComplete.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Gemini Live не подтвердил setup за 20 секунд");
                }
                if (replayHistory) {
                    sendInitialHistory(initialHistory);
                    log.info("Gemini recovery history replayed: turns={}", initialHistory.size());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                safeClose(newSocket, "interrupted");
                throw new IllegalStateException("Подключение Gemini прервано", ex);
            } catch (RuntimeException ex) {
                safeClose(newSocket, "setup-failed");
                throw ex;
            }

            ready = true;
            readyLatch.countDown();
            reconnecting.set(false);
            if (previous != null && previous != newSocket) {
                safeClose(previous, "replaced");
            }
            log.info("Gemini Live ready; resumed={}, replayed={}",
                    handle != null && !handle.isBlank(), replayHistory);
        }
    }

    private JsonNode setupMessage(String model, String handle, boolean replayHistory) {
        String normalized = model != null && model.startsWith("models/") ? model : "models/" + model;
        ObjectNode setup = mapper.createObjectNode();
        setup.put("model", normalized);
        ObjectNode generationConfig = mapper.createObjectNode();
        generationConfig.set("responseModalities", mapper.createArrayNode().add("AUDIO"));
        generationConfig.set("thinkingConfig", mapper.createObjectNode().put("thinkingLevel", "MINIMAL"));
        setup.set("generationConfig", generationConfig);
        setup.set("inputAudioTranscription", mapper.createObjectNode());
        setup.set("outputAudioTranscription", mapper.createObjectNode());
        setup.set("realtimeInputConfig", mapper.createObjectNode()
                .set("automaticActivityDetection", mapper.createObjectNode().put("disabled", true)));
        setup.set("contextWindowCompression", mapper.createObjectNode()
                .set("slidingWindow", mapper.createObjectNode()));

        ObjectNode resumption = mapper.createObjectNode();
        if (handle != null && !handle.isBlank()) resumption.put("handle", handle);
        setup.set("sessionResumption", resumption);

        if (replayHistory) {
            setup.set("historyConfig", mapper.createObjectNode().put("initialHistoryInClientContent", true));
        }
        return mapper.createObjectNode().set("setup", setup);
    }

    private void sendInitialHistory(List<HistoryTurn> history) {
        ArrayNode turns = mapper.createArrayNode();
        for (HistoryTurn turn : history) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) continue;
            String role = "model".equalsIgnoreCase(turn.role()) ? "model" : "user";
            ObjectNode content = mapper.createObjectNode();
            content.put("role", role);
            content.set("parts", mapper.createArrayNode().add(
                    mapper.createObjectNode().put("text", turn.text().trim())));
            turns.add(content);
        }
        if (turns.isEmpty()) return;
        ObjectNode clientContent = mapper.createObjectNode();
        clientContent.set("turns", turns);
        clientContent.put("turnComplete", true);
        sendJson(mapper.createObjectNode().set("clientContent", clientContent));
    }

    private void scheduleReconnect(String reason) {
        if (!desiredOpen.get()) return;
        if (!reconnecting.compareAndSet(false, true)) return;

        ready = false;
        readyLatch = new CountDownLatch(1);
        listener.onStatus("Восстанавливаю соединение Gemini…");
        log.warn("Gemini recovery scheduled: reason={}, haveHandle={}", reason,
                resumptionHandle != null && !resumptionHandle.isBlank());

        reconnectExecutor.execute(() -> recoverConnection(reason));
    }

    private void recoverConnection(String reason) {
        Throwable last = null;
        String handle = resumptionHandle;

        if (handle != null && !handle.isBlank()) {
            for (int attempt = 1; attempt <= RESUME_ATTEMPTS && desiredOpen.get(); attempt++) {
                try {
                    if (attempt > 1) sleepBackoff(attempt);
                    openSocket(handle, List.of());
                    listener.onStatus("Слушаю звонок");
                    log.info("Gemini session resumed successfully on attempt {}", attempt);
                    return;
                } catch (Throwable ex) {
                    last = ex;
                    log.warn("Gemini resumption attempt {} failed: {}", attempt, ex.toString());
                }
            }
        } else {
            log.info("Gemini closed before a resumption handle was available; switching to fresh-session recovery");
        }

        if (recoverySupport != null) {
            for (int attempt = 1; attempt <= FRESH_RECOVERY_ATTEMPTS && desiredOpen.get(); attempt++) {
                try {
                    if (attempt > 1) sleepBackoff(attempt);
                    listener.onStatus("Восстанавливаю контекст разговора…");
                    LiveSessionDescriptor renewed = recoverySupport.renewDescriptor();
                    if (renewed == null || renewed.ephemeralToken() == null || renewed.ephemeralToken().isBlank()) {
                        throw new IllegalStateException("Сервер не выдал новый ephemeral token для восстановления");
                    }
                    descriptor = renewed;
                    resumptionHandle = "";
                    resetTransientTurnState();
                    List<HistoryTurn> history = recoverySupport.historySnapshot();
                    if (history == null) history = List.of();
                    openSocket("", history);
                    listener.onStatus("Слушаю звонок");
                    log.info("Gemini fresh-session recovery succeeded: attempt={}, replayTurns={}, originalReason={}",
                            attempt, history.size(), reason);
                    return;
                } catch (Throwable ex) {
                    last = ex;
                    log.warn("Gemini fresh-session recovery attempt {} failed: {}", attempt, ex.toString());
                }
            }
        }

        reconnecting.set(false);
        ready = false;
        readyLatch.countDown();
        if (desiredOpen.get()) {
            listener.onError(last == null
                    ? new IllegalStateException("Не удалось автоматически восстановить Gemini Live: " + reason)
                    : new IllegalStateException("Не удалось автоматически восстановить Gemini Live: " + rootMessage(last), last));
        }
    }

    private void sleepBackoff(int attempt) throws InterruptedException {
        Thread.sleep(Math.min(4_500L, 700L + attempt * 900L));
    }

    private void sendJson(JsonNode message) {
        WebSocket active = socket;
        if (active == null) throw new IllegalStateException("Gemini WebSocket ещё не создан");
        try {
            active.sendText(message.toString(), true).join();
            sentMessages++;
        } catch (CompletionException ex) {
            throw new IllegalStateException("Ошибка отправки в Gemini Live: " + rootMessage(unwrap(ex)), unwrap(ex));
        }
    }

    private void handleMessage(String json, WebSocket source, ConnectionListener connection) {
        if (source != socket) {
            log.debug("Ignoring Gemini message from superseded WebSocket");
            return;
        }
        try {
            JsonNode root = mapper.readTree(json);
            receivedMessages++;
            if (root.has("setupComplete")) {
                connection.setupComplete.countDown();
                return;
            }
            if (root.has("error")) {
                String errorText = root.path("error").toString();
                log.warn("Gemini Live server error message: {}", errorText);
                scheduleReconnect("Gemini Live вернул ошибку: " + errorText);
                return;
            }

            JsonNode resume = root.path("sessionResumptionUpdate");
            if (!resume.isMissingNode()) {
                boolean resumable = resume.path("resumable").asBoolean(false);
                String newHandle = resume.path("newHandle").asText("");
                if (newHandle.isBlank()) newHandle = resume.path("token").asText("");
                if (resumable && !newHandle.isBlank()) {
                    resumptionHandle = newHandle;
                    log.debug("Gemini session resumption handle updated: chars={}", newHandle.length());
                }
            }

            JsonNode goAway = root.path("goAway");
            if (!goAway.isMissingNode()) {
                log.info("Gemini GoAway received: timeLeft={}", goAway.path("timeLeft").asText(""));
                scheduleReconnect("Gemini запросил плановое переподключение");
                return;
            }

            JsonNode content = root.path("serverContent");
            if (content.isMissingNode()) return;

            SpeakerRole turnRole;
            long turnSerial;
            synchronized (turnStateLock) {
                turnRole = activeTurnRole;
                turnSerial = activeTurnSerial;
            }
            if (turnRole == null) {
                log.debug("Ignoring Gemini serverContent without an active local utterance");
                return;
            }

            String input = content.path("inputTranscription").path("text").asText("");
            if (!input.isBlank()) {
                String currentTranscript;
                synchronized (turnStateLock) {
                    if (turnSerial != activeTurnSerial) return;
                    inputTranscript.append(input);
                    currentTranscript = inputTranscript.toString().trim();
                }
                listener.onTranscript(turnRole, currentTranscript, false);
            }

            String output = content.path("outputTranscription").path("text").asText("");
            if (!output.isBlank() && turnRole == SpeakerRole.CUSTOMER) {
                String currentSuggestion;
                synchronized (turnStateLock) {
                    if (turnSerial != activeTurnSerial) return;
                    suggestion.append(output);
                    currentSuggestion = suggestion.toString().trim();
                }
                listener.onSuggestion(currentSuggestion, false);
            }

            if (content.path("interrupted").asBoolean(false)) {
                synchronized (turnStateLock) {
                    if (turnSerial == activeTurnSerial) suggestion.setLength(0);
                }
            }
            if (content.path("turnComplete").asBoolean(false)) {
                scheduleTurnFinalization(turnSerial);
            }
        } catch (Throwable ex) {
            log.error("Failed to process Gemini Live message", ex);
            listener.onError(ex);
        }
    }

    private void scheduleTurnFinalization(long turnSerial) {
        synchronized (turnStateLock) {
            if (turnSerial != activeTurnSerial || activeTurnRole == null || finalizationScheduled) return;
            finalizationScheduled = true;
        }
        // Google documents that audio transcriptions are delivered independently of
        // turnComplete and have no guaranteed ordering. A short settling window lets
        // late transcription fragments join the same immutable chat message before
        // the next locally buffered utterance is released.
        reconnectExecutor.schedule(() -> finalizeTurn(turnSerial, false),
                TRANSCRIPTION_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void finalizeTurn(long turnSerial, boolean timedOut) {
        SpeakerRole role;
        String transcript;
        String completeSuggestion;
        synchronized (turnStateLock) {
            if (turnSerial != activeTurnSerial || activeTurnRole == null) return;
            role = activeTurnRole;
            transcript = inputTranscript.toString().trim();
            completeSuggestion = suggestion.toString().trim();
            inputTranscript.setLength(0);
            suggestion.setLength(0);
            activeTurnRole = null;
            inputOpen = false;
            finalizationScheduled = false;
        }

        if (!transcript.isBlank()) listener.onTranscript(role, transcript, true);
        if (role == SpeakerRole.CUSTOMER && !completeSuggestion.isBlank()
                && !completeSuggestion.equals("—") && !completeSuggestion.equals("-")) {
            listener.onSuggestion(completeSuggestion, true);
        }
        if (desiredOpen.get() && !reconnecting.get()) listener.onStatus("Слушаю звонок");
        log.debug("Gemini turn finalized: turn={}, role={}, timedOut={}, transcriptChars={}, suggestionChars={}",
                turnSerial, role, timedOut, transcript.length(), completeSuggestion.length());
    }

    private void releaseTurn(long turnSerial) {
        synchronized (turnStateLock) {
            if (turnSerial != activeTurnSerial) return;
            inputTranscript.setLength(0);
            suggestion.setLength(0);
            activeTurnRole = null;
            inputOpen = false;
            finalizationScheduled = false;
        }
    }

    private void resetTransientTurnState() {
        synchronized (turnStateLock) {
            activeTurnSerial++;
            activeTurnRole = null;
            inputOpen = false;
            finalizationScheduled = false;
            inputTranscript.setLength(0);
            suggestion.setLength(0);
        }
    }

    @Override
    public void close() {
        desiredOpen.set(false);
        ready = false;
        readyLatch.countDown();
        resetTransientTurnState();
        reconnectExecutor.shutdownNow();
        WebSocket active = socket;
        socket = null;
        if (active != null) safeClose(active, "stop");
        log.info("Gemini Live closed: sentMessages={}, receivedMessages={}, audioBytes={}",
                sentMessages, receivedMessages, sentAudioBytes);
    }

    private void safeClose(WebSocket webSocket, String reason) {
        try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason); } catch (RuntimeException ignored) {}
    }

    private String buildUrl(String endpoint, String token) {
        String base = endpoint == null || endpoint.isBlank()
                ? "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
                : endpoint.trim();
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        return base + (base.contains("?") ? "&" : "?") + "access_token=" + encoded;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public interface RecoverySupport {
        LiveSessionDescriptor renewDescriptor();
        List<HistoryTurn> historySnapshot();
    }

    public record HistoryTurn(String role, String text) {}

    private final class ConnectionListener implements WebSocket.Listener {
        private final CountDownLatch setupComplete = new CountDownLatch(1);
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                handleMessage(message, webSocket, this);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.writeBytes(chunk);
            if (last) {
                byte[] message = binaryBuffer.toByteArray();
                binaryBuffer.reset();
                handleMessage(new String(message, StandardCharsets.UTF_8), webSocket, this);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Gemini WebSocket closed: code={}, reason={}", statusCode, reason);
            if (desiredOpen.get() && webSocket == socket) {
                scheduleReconnect("Gemini Live закрыл соединение: " + statusCode
                        + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Gemini WebSocket error: {}", error.toString());
            if (desiredOpen.get() && webSocket == socket) scheduleReconnect("Ошибка соединения Gemini Live");
        }
    }
}
