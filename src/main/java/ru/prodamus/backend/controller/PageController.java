package ru.prodamus.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import ru.prodamus.backend.security.AdminSession;

@Controller
public class PageController {
    @GetMapping("/")
    public String root() { return "redirect:/backoffice"; }

    @GetMapping("/backoffice")
    public String backoffice(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(AdminSession.AUTHENTICATED))) {
            return "redirect:/backoffice.html";
        }
        return "redirect:/login.html";
    }
}
