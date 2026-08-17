package ru.prodamus.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import ru.prodamus.backend.security.ClientAuthInterceptor;
import ru.prodamus.backend.service.ClientAuthService;
import ru.prodamus.backend.service.ClientBootstrapService;
import ru.prodamus.backend.service.LiveSessionService;

@RestController
@RequestMapping("/api/client")
public class ClientApiController {
    private final ClientBootstrapService bootstrap;
    private final LiveSessionService liveSessions;

    public ClientApiController(ClientBootstrapService bootstrap, LiveSessionService liveSessions) {
        this.bootstrap = bootstrap; this.liveSessions = liveSessions;
    }

    @GetMapping("/bootstrap")
    public ClientBootstrapService.Bootstrap bootstrap(HttpServletRequest request,
            @RequestHeader(value = "X-Prodamus-Client-Version", required = false) String version) {
        ClientAuthService.AuthenticatedClient client = client(request);
        return bootstrap.bootstrap(client.userId(), version);
    }

    @PostMapping("/live-sessions")
    public LiveSessionService.SessionDescriptor start(@Valid @RequestBody StartSessionRequest body,
                                                       HttpServletRequest request) {
        ClientAuthService.AuthenticatedClient client = client(request);
        ClientBootstrapService.Bootstrap state = bootstrap.bootstrap(client.userId(), body.clientVersion());
        if (state.version().updateRequired()) {
            throw ApiException.conflict("CLIENT_UPDATE_REQUIRED", "Версия приложения больше не поддерживается. Обновите Prodamus.");
        }
        return liveSessions.start(client.userId(), client.deviceId(), body.promptProfileId(), body.deviceId(),
                body.clientVersion(), body.clientContext());
    }

    private ClientAuthService.AuthenticatedClient client(HttpServletRequest request) {
        Object value = request.getAttribute(ClientAuthInterceptor.CLIENT);
        if (value instanceof ClientAuthService.AuthenticatedClient client) return client;
        throw ApiException.unauthorized("Клиент не авторизован.");
    }

    public record StartSessionRequest(@NotNull(message = "Выберите роль.") Long promptProfileId,
                                      String deviceId, String clientVersion, String clientContext) {}
}
