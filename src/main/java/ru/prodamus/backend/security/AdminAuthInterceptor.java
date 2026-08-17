package ru.prodamus.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import ru.prodamus.backend.controller.ApiException;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(AdminSession.AUTHENTICATED))) {
            throw ApiException.unauthorized("Сессия back-office завершена.");
        }
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method) && !HttpMethod.OPTIONS.matches(method)) {
            String expected = (String) session.getAttribute(AdminSession.CSRF);
            String actual = request.getHeader("X-Backoffice-CSRF");
            if (expected == null || actual == null || !java.security.MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw ApiException.forbidden("Неверный CSRF-токен back-office.");
            }
        }
        return true;
    }
}
