package ru.prodamus.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import ru.prodamus.backend.security.AdminSession;
import ru.prodamus.backend.security.TokenService;
import ru.prodamus.backend.service.AuditService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final String configuredLogin;
    private final String configuredPassword;
    private final TokenService tokens;
    private final AuditService audit;

    public AdminAuthController(@Value("${prodamus.admin.login}") String configuredLogin,
                               @Value("${prodamus.admin.password}") String configuredPassword,
                               TokenService tokens, AuditService audit) {
        this.configuredLogin = configuredLogin;
        this.configuredPassword = configuredPassword;
        this.tokens = tokens;
        this.audit = audit;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        boolean ok = constant(request.login().trim(), configuredLogin) && constant(request.password(), configuredPassword);
        if (!ok) {
            audit.record("ADMIN_LOGIN_FAILED", request.login(), servletRequest.getRemoteAddr(), null);
            throw ApiException.unauthorized("Неверный логин или пароль.");
        }
        HttpSession old = servletRequest.getSession(false);
        if (old != null) old.invalidate();
        HttpSession session = servletRequest.getSession(true);
        servletRequest.changeSessionId();
        String csrf = tokens.randomToken();
        session.setAttribute(AdminSession.AUTHENTICATED, true);
        session.setAttribute(AdminSession.LOGIN, configuredLogin);
        session.setAttribute(AdminSession.CSRF, csrf);
        audit.record("ADMIN_LOGIN", configuredLogin, servletRequest.getRemoteAddr(), null);
        return Map.of("ok", true, "login", configuredLogin, "csrf", csrf);
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(AdminSession.AUTHENTICATED))) {
            throw ApiException.unauthorized("Сессия back-office завершена.");
        }
        return Map.of("authenticated", true,
                "login", String.valueOf(session.getAttribute(AdminSession.LOGIN)),
                "csrf", String.valueOf(session.getAttribute(AdminSession.CSRF)));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("ok", true);
    }

    private boolean constant(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public record LoginRequest(@NotBlank(message = "Введите логин.") String login,
                               @NotBlank(message = "Введите пароль.") String password) {}
}
