package ru.prodamus.client.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.prodamus.client.config.SettingsService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BackendClient {
    private static final Logger log = LoggerFactory.getLogger(BackendClient.class);
    private final ObjectMapper mapper;
    private final SettingsService settings;
    private final HttpClient http;
    private final String baseUrl;
    private final String clientVersion;
    private final Object authLock = new Object();

    private volatile String accessToken = "";
    private volatile Instant accessExpiresAt = Instant.EPOCH;
    private volatile String refreshToken = "";
    private volatile boolean persistent;
    private volatile AuthResponse authState;

    public BackendClient(ObjectMapper mapper, SettingsService settings,
                         @Value("${prodamus.server.base-url:https://prodamus.abs7.ru}") String baseUrl,
                         @Value("${prodamus.client.version:1.0.1}") String clientVersion) {
        this.mapper = mapper;
        this.settings = settings;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.clientVersion = clientVersion.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String clientVersion() { return clientVersion; }
    public String baseUrl() { return baseUrl; }
    public String deviceId() { return settings.deviceId(); }
    public AuthResponse authState() { return authState; }

    public boolean restoreRememberedSession() {
        String stored = settings.loadRefreshToken();
        if (stored.isBlank()) return false;
        synchronized (authLock) {
            refreshToken = stored;
            persistent = true;
            try {
                applyAuth(refresh(refreshToken));
                log.info("Remembered Prodamus session restored for user={}", authState == null ? "<unknown>" : authState.login());
                return true;
            } catch (RuntimeException ex) {
                log.info("Stored Prodamus session cannot be restored: {}", ex.getMessage());
                clearAuth();
                if (ex instanceof BackendException backendError
                        && (backendError.statusCode() == 401 || backendError.statusCode() == 403)) {
                    settings.clearRefreshToken();
                }
                return false;
            }
        }
    }

    public AuthResponse login(String login, String password, boolean rememberMe) {
        LoginRequest body = new LoginRequest(login, password, deviceId(), settings.deviceName(), rememberMe);
        AuthResponse response = postPublic("/api/client/auth/login", body, AuthResponse.class);
        synchronized (authLock) { applyAuth(response); }
        settings.setLastLogin(login);
        return response;
    }

    public void logout() {
        String access = accessToken;
        String refresh = refreshToken;
        try {
            HttpRequest.Builder builder = request("/api/client/auth/logout")
                    .POST(jsonBody(new LogoutRequest(refresh)));
            if (!access.isBlank()) builder.header("Authorization", "Bearer " + access);
            send(builder.build(), JsonNode.class, false);
        } catch (RuntimeException ex) {
            log.debug("Logout request failed; clearing local session anyway: {}", ex.getMessage());
        } finally {
            synchronized (authLock) { clearAuth(); }
            settings.clearRefreshToken();
        }
    }

    public Bootstrap bootstrap() {
        HttpRequest request = authorizedBuilder("/api/client/bootstrap")
                .header("X-Prodamus-Client-Version", clientVersion)
                .GET().build();
        return sendAuthorized(request, Bootstrap.class);
    }

    public LiveSessionDescriptor startLiveSession(long promptProfileId, String clientContext) {
        StartSessionRequest body = new StartSessionRequest(promptProfileId, deviceId(), clientVersion, clientContext);
        return authorizedPost("/api/client/live-sessions", body, LiveSessionDescriptor.class);
    }

    private <T> T authorizedPost(String path, Object body, Class<T> type) {
        HttpRequest request = authorizedBuilder(path).POST(body == null ? HttpRequest.BodyPublishers.noBody() : jsonBody(body)).build();
        return sendAuthorized(request, type);
    }

    private <T> T sendAuthorized(HttpRequest original, Class<T> type) {
        ensureAccessToken();
        HttpRequest first = withAuthorization(original, accessToken);
        try {
            return send(first, type, true);
        } catch (BackendException ex) {
            if (ex.statusCode() != 401) throw ex;
            synchronized (authLock) {
                if (refreshToken.isBlank()) throw ex;
                applyAuth(refresh(refreshToken));
            }
            return send(withAuthorization(original, accessToken), type, true);
        }
    }

    private void ensureAccessToken() {
        if (!accessToken.isBlank() && accessExpiresAt.isAfter(Instant.now().plusSeconds(30))) return;
        synchronized (authLock) {
            if (!accessToken.isBlank() && accessExpiresAt.isAfter(Instant.now().plusSeconds(30))) return;
            if (refreshToken.isBlank()) throw new BackendException(401, "UNAUTHORIZED", "Сессия завершена. Войдите снова.");
            applyAuth(refresh(refreshToken));
        }
    }

    private AuthResponse refresh(String token) {
        return postPublic("/api/client/auth/refresh",
                new RefreshRequest(token, deviceId(), settings.deviceName()), AuthResponse.class);
    }

    private void applyAuth(AuthResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new BackendException(401, "AUTH_INVALID", "Сервер вернул некорректную сессию входа.");
        }
        accessToken = response.accessToken();
        accessExpiresAt = response.accessExpiresAt() == null ? Instant.now().plusSeconds(60) : response.accessExpiresAt();
        refreshToken = response.refreshToken() == null ? "" : response.refreshToken();
        persistent = response.persistent();
        authState = response;
        if (persistent && !refreshToken.isBlank()) settings.storeRefreshToken(refreshToken);
        else settings.clearRefreshToken();
    }

    private void clearAuth() {
        accessToken = "";
        accessExpiresAt = Instant.EPOCH;
        refreshToken = "";
        persistent = false;
        authState = null;
    }

    private <T> T postPublic(String path, Object body, Class<T> type) {
        HttpRequest request = request(path).POST(jsonBody(body)).build();
        return send(request, type, false);
    }

    private HttpRequest.Builder authorizedBuilder(String path) {
        return request(path).header("X-Prodamus-Client-Version", clientVersion);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Prodamus-Windows/" + clientVersion);
    }

    private HttpRequest.BodyPublisher jsonBody(Object value) {
        try {
            return HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value));
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сформировать запрос к серверу", ex);
        }
    }

    private HttpRequest withAuthorization(HttpRequest source, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(source.uri())
                .timeout(source.timeout().orElse(Duration.ofSeconds(25)))
                .method(source.method(), source.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        source.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        builder.setHeader("Authorization", "Bearer " + token);
        return builder.build();
    }

    private <T> T send(HttpRequest request, Class<T> type, boolean authenticated) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw serverError(response.statusCode(), response.body());
            }
            if (type == Void.class) return null;
            if (type == String.class) return type.cast(response.body());
            return mapper.readValue(response.body(), type);
        } catch (BackendException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BackendException(0, "INTERRUPTED", "Запрос к серверу прерван.");
        } catch (IOException ex) {
            log.warn("Backend request failed: {} {}: {}", request.method(), request.uri(), ex.toString());
            throw new BackendException(0, "NETWORK_ERROR", "Не удалось связаться с сервером Prodamus.");
        }
    }

    private BackendException serverError(int status, String body) {
        try {
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            String code = json.path("code").asText("HTTP_" + status);
            String message = json.path("message").asText("Ошибка сервера Prodamus (HTTP " + status + ")");
            return new BackendException(status, code, message);
        } catch (Exception ignored) {
            return new BackendException(status, "HTTP_" + status, "Ошибка сервера Prodamus (HTTP " + status + ")");
        }
    }

    public record LoginRequest(String login, String password, String deviceId, String deviceName, boolean rememberMe) {}
    public record RefreshRequest(String refreshToken, String deviceId, String deviceName) {}
    public record LogoutRequest(String refreshToken) {}
    public record AuthResponse(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt,
                               boolean persistent, Long userId, String login, String displayName) {}
    public record Bootstrap(UserInfo user, List<Role> roles, Features features, VersionInfo version) {}
    public record UserInfo(Long id, String login, String displayName) {}
    public record Role(Long id, String name, String description) {
        @Override public String toString() { return name == null || name.isBlank() ? "Роль" : name; }
    }
    public record Features(boolean expandedMode, boolean manualClientContext) {}
    public record VersionInfo(String minimumSupported, String latest, String downloadUrl,
                              boolean updateRequired, boolean updateAvailable) {}
    public record StartSessionRequest(long promptProfileId, String deviceId, String clientVersion, String clientContext) {}
    public record LiveSessionDescriptor(UUID sessionId, String ephemeralToken, Instant tokenExpiresAt,
                                        Instant newSessionExpiresAt, String websocketUrl, String model) {}

    public static final class BackendException extends RuntimeException {
        private final int statusCode;
        private final String code;
        public BackendException(int statusCode, String code, String message) {
            super(message);
            this.statusCode = statusCode;
            this.code = code;
        }
        public int statusCode() { return statusCode; }
        public String code() { return code; }
    }
}
