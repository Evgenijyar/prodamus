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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Прямой клиент Gemini Live. Класс намеренно повторяет простой тракт Sales Helper:
 * один WebSocket, цельная реплика, activityStart/audio/activityEnd и немедленное
 * отображение фрагментов outputTranscription. Backend в этом тракте отсутствует.
 */
public final class GeminiLiveClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeminiLiveClient.class);
    private static final int AUDIO_CHUNK_BYTES = 3_200;

    private final ObjectMapper mapper;
    private final GeminiEventListener listener;
    private final HttpClient httpClient;
    private final CountDownLatch setupComplete = new CountDownLatch(1);
    private final AtomicBoolean open = new AtomicBoolean();
    private final Object sendLock = new Object();
    private final StringBuilder suggestion = new StringBuilder();
    private final StringBuilder responseBuffer = new StringBuilder();
    private final ByteArrayOutputStream binaryResponseBuffer = new ByteArrayOutputStream();

    private volatile WebSocket socket;
    private long sentAudioBytes;
    private long sentMessages;
    private long receivedMessages;

    public GeminiLiveClient(ObjectMapper mapper, GeminiEventListener listener) {
        this.mapper = mapper;
        this.listener = listener;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public void connect(LiveSessionDescriptor descriptor) {
        if (descriptor == null || descriptor.ephemeralToken() == null || descriptor.ephemeralToken().isBlank()) {
            throw new IllegalArgumentException("Сервер не выдал временный ключ Gemini");
        }
        listener.onStatus("Подключение к Gemini Live…");
        URI uri = URI.create(buildUrl(descriptor.websocketUrl(), descriptor.ephemeralToken()));
        log.info("Direct Gemini Live connection requested: model={}", descriptor.model());
        try {
            socket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(uri, new SocketListener())
                    .orTimeout(25, TimeUnit.SECONDS)
                    .join();
        } catch (CompletionException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof WebSocketHandshakeException handshake) {
                throw new IllegalStateException("Gemini Live отклонил подключение (HTTP "
                        + handshake.getResponse().statusCode() + ")", cause);
            }
            throw new IllegalStateException("Не удалось подключиться к Gemini Live: " + rootMessage(cause), cause);
        }

        sendJson(setupMessage(descriptor.model()));
        try {
            if (!setupComplete.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Gemini Live не подтвердил настройку сессии за 20 секунд");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Подключение Gemini прервано", exception);
        }
        open.set(true);
        log.info("Direct Gemini Live session is ready");
        listener.onStatus("Слушаю звонок");
    }

    public void sendUtterance(SpeakerRole role, byte[] pcm) {
        if (!open.get() || pcm == null || pcm.length == 0) return;
        synchronized (sendLock) {
            log.info("Sending complete utterance directly to Gemini: role={}, bytes={}, durationMs={}",
                    role, pcm.length, pcm.length * 1000L / 32_000L);
            listener.onStatus("Распознана реплика: " + role.label().toLowerCase());
            sendJson(mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().set("activityStart", mapper.createObjectNode())));
            sendJson(mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().put("text", "[" + role.label() + "]")));
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
            sendJson(mapper.createObjectNode().set("realtimeInput",
                    mapper.createObjectNode().set("activityEnd", mapper.createObjectNode())));
        }
    }

    private JsonNode setupMessage(String model) {
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
        return mapper.createObjectNode().set("setup", setup);
    }

    private void sendJson(JsonNode message) {
        WebSocket active = socket;
        if (active == null) throw new IllegalStateException("Gemini WebSocket ещё не создан");
        try {
            active.sendText(message.toString(), true).join();
            sentMessages++;
        } catch (CompletionException exception) {
            Throwable cause = unwrap(exception);
            throw new IllegalStateException("Ошибка отправки в Gemini Live: " + rootMessage(cause), cause);
        }
    }

    private void handleMessage(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            receivedMessages++;
            if (root.has("setupComplete")) {
                setupComplete.countDown();
                return;
            }
            if (root.has("error")) {
                throw new IllegalStateException("Gemini Live: " + root.path("error"));
            }
            JsonNode content = root.path("serverContent");
            if (content.isMissingNode()) return;

            String input = content.path("inputTranscription").path("text").asText("");
            if (!input.isBlank()) listener.onTranscript(input);

            String output = content.path("outputTranscription").path("text").asText("");
            if (!output.isBlank()) {
                suggestion.append(output);
                listener.onSuggestion(suggestion.toString().trim(), false);
            }
            if (content.path("interrupted").asBoolean(false)) {
                String interrupted = suggestion.toString().trim();
                if (!interrupted.isBlank() && !interrupted.equals("—") && !interrupted.equals("-")) {
                    // Активное слушание может прервать генерацию следующим аудиофрагментом.
                    // Уже показанный текст фиксируется отдельной подсказкой и не затирается.
                    listener.onSuggestion(interrupted, true);
                }
                suggestion.setLength(0);
            }
            if (content.path("turnComplete").asBoolean(false)) {
                String complete = suggestion.toString().trim();
                if (!complete.isBlank() && !complete.equals("—") && !complete.equals("-")) {
                    listener.onSuggestion(complete, true);
                }
                suggestion.setLength(0);
                listener.onStatus("Слушаю звонок");
            }
        } catch (Throwable exception) {
            log.error("Failed to process Gemini Live message", exception);
            listener.onError(exception);
        }
    }

    @Override
    public void close() {
        boolean wasOpen = open.getAndSet(false);
        WebSocket active = socket;
        socket = null;
        if (active != null) {
            try { active.sendClose(WebSocket.NORMAL_CLOSURE, "stop"); }
            catch (RuntimeException ignored) { }
        }
        log.info("Gemini Live closed: wasOpen={}, sentMessages={}, receivedMessages={}, sentAudioBytes={}",
                wasOpen, sentMessages, receivedMessages, sentAudioBytes);
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
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private final class SocketListener implements WebSocket.Listener {
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
                handleMessage(message);
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
                handleMessage(new String(message, StandardCharsets.UTF_8));
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            open.set(false);
            log.info("Gemini WebSocket closed: statusCode={}, reason={}", statusCode, reason);
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                listener.onError(new IllegalStateException(
                        "Gemini Live закрыл соединение: " + statusCode + " " + reason));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            open.set(false);
            log.error("Gemini WebSocket error", error);
            listener.onError(error);
        }
    }
}
