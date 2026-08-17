package ru.prodamus.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.prodamus.backend.service.AuditService;
import ru.prodamus.backend.service.ClientAuthService;

@RestController
@RequestMapping("/api/client/auth")
public class ClientAuthController {
    private final ClientAuthService auth;
    private final AuditService audit;

    public ClientAuthController(ClientAuthService auth, AuditService audit) { this.auth = auth; this.audit = audit; }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        ClientAuthService.TokenPair pair = auth.login(request.login(), request.password(), request.deviceId(), request.deviceName(), request.rememberMe());
        audit.record("CLIENT_LOGIN", pair.user().getLogin(), request.deviceId(), servletRequest.getRemoteAddr());
        return response(pair);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return response(auth.refresh(request.refreshToken(), request.deviceId(), request.deviceName()));
    }

    @PostMapping("/logout")
    public java.util.Map<String, Object> logout(@RequestBody(required = false) LogoutRequest request,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        String access = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).trim() : null;
        auth.logout(access, request == null ? null : request.refreshToken());
        return java.util.Map.of("ok", true);
    }

    private AuthResponse response(ClientAuthService.TokenPair pair) {
        return new AuthResponse(pair.accessToken(), pair.accessExpiresAt(), pair.refreshToken(), pair.refreshExpiresAt(),
                pair.persistent(), pair.user().getId(), pair.user().getLogin(), pair.user().getDisplayName());
    }

    public record LoginRequest(@NotBlank(message = "Введите логин.") String login,
                               @NotBlank(message = "Введите пароль.") String password,
                               @NotBlank(message = "deviceId обязателен.") String deviceId,
                               String deviceName, boolean rememberMe) {}
    public record RefreshRequest(@NotBlank(message = "refreshToken обязателен.") String refreshToken,
                                 @NotBlank(message = "deviceId обязателен.") String deviceId, String deviceName) {}
    public record LogoutRequest(String refreshToken) {}
    public record AuthResponse(String accessToken, java.time.Instant accessExpiresAt, String refreshToken,
                               java.time.Instant refreshExpiresAt, boolean persistent, Long userId,
                               String login, String displayName) {}
}
