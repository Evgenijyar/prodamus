package ru.prodamus.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import ru.prodamus.backend.controller.ApiException;
import ru.prodamus.backend.service.ClientAuthService;

@Component
public class ClientAuthInterceptor implements HandlerInterceptor {
    public static final String CLIENT = "PRODAMUS_AUTHENTICATED_CLIENT";
    private final ClientAuthService auth;

    public ClientAuthInterceptor(ClientAuthService auth) { this.auth = auth; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw ApiException.unauthorized("Bearer access token не передан.");
        }
        ClientAuthService.AuthenticatedClient client = auth.authenticate(header.substring(7).trim());
        request.setAttribute(CLIENT, client);
        return true;
    }
}
