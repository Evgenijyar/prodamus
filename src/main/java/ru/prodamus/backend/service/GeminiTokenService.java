package ru.prodamus.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.prodamus.backend.controller.ApiException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiTokenService {
    private final RestClient restClient;
    private final String tokenUrl;
    private final String websocketUrl;
    private final long tokenExpireMinutes;
    private final long newSessionExpireSeconds;

    public GeminiTokenService(RestClient.Builder builder,
                              @Value("${prodamus.gemini.auth-token-url}") String tokenUrl,
                              @Value("${prodamus.gemini.websocket-url}") String websocketUrl,
                              @Value("${prodamus.gemini.token-expire-minutes:240}") long tokenExpireMinutes,
                              @Value("${prodamus.gemini.new-session-expire-seconds:60}") long newSessionExpireSeconds,
                              @Value("${prodamus.gemini.http-timeout-seconds:15}") long httpTimeoutSeconds) {
        Duration timeout = Duration.ofSeconds(Math.max(3, httpTimeoutSeconds));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder.requestFactory(requestFactory).build();
        this.tokenUrl = tokenUrl;
        this.websocketUrl = websocketUrl;
        this.tokenExpireMinutes = Math.max(5, Math.min(240, tokenExpireMinutes));
        this.newSessionExpireSeconds = Math.max(10, Math.min(60, newSessionExpireSeconds));
    }

    @SuppressWarnings("unchecked")
    public TokenResult createConstrainedToken(String apiKey, String model, String systemInstruction) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenExpireMinutes, ChronoUnit.MINUTES);
        Instant newSessionExpiresAt = now.plus(newSessionExpireSeconds, ChronoUnit.SECONDS);

        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("model", model.startsWith("models/") ? model : "models/" + model);
        setup.put("generationConfig", Map.of("responseModalities", List.of("AUDIO")));
        setup.put("inputAudioTranscription", Map.of());
        setup.put("outputAudioTranscription", Map.of());
        setup.put("realtimeInputConfig", Map.of("automaticActivityDetection", Map.of("disabled", true)));
        setup.put("contextWindowCompression", Map.of("slidingWindow", Map.of()));

        List<String> lockedFields = new java.util.ArrayList<>(List.of(
                "model",
                "generationConfig",
                "inputAudioTranscription",
                "outputAudioTranscription",
                "realtimeInputConfig",
                "contextWindowCompression"
        ));
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            setup.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
            lockedFields.add("systemInstruction");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uses", 1);
        request.put("expireTime", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
        request.put("newSessionExpireTime", newSessionExpiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
        request.put("bidiGenerateContentSetup", setup);
        request.put("fieldMask", String.join(",", lockedFields));

        try {
            Map<String, Object> result = restClient.post().uri(tokenUrl)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            String token = result == null ? null : String.valueOf(result.getOrDefault("name", ""));
            if (token == null || token.isBlank() || "null".equals(token)) {
                throw ApiException.unavailable("GEMINI_TOKEN_INVALID", "Gemini не вернул ephemeral token.");
            }
            return new TokenResult(token, expiresAt, newSessionExpiresAt, websocketUrl, normalizeModel(model));
        } catch (RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            String compact = body == null || body.isBlank() ? ex.getStatusText() : body;
            if (compact.length() > 1200) compact = compact.substring(0, 1200);
            throw ApiException.unavailable("GEMINI_TOKEN_ERROR", "Gemini отклонил создание сессии: " + compact);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ApiException.unavailable("GEMINI_UNAVAILABLE", "Не удалось связаться с Gemini: " + ex.getMessage());
        }
    }

    private String normalizeModel(String model) { return model.startsWith("models/") ? model.substring("models/".length()) : model; }

    public record TokenResult(String ephemeralToken, Instant expiresAt, Instant newSessionExpiresAt,
                              String websocketUrl, String model) {}
}
