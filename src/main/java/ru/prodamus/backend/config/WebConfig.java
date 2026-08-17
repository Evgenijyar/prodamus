package ru.prodamus.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.prodamus.backend.security.AdminAuthInterceptor;
import ru.prodamus.backend.security.ClientAuthInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AdminAuthInterceptor adminAuth;
    private final ClientAuthInterceptor clientAuth;

    public WebConfig(AdminAuthInterceptor adminAuth, ClientAuthInterceptor clientAuth) {
        this.adminAuth = adminAuth;
        this.clientAuth = clientAuth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuth)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/auth/login");
        registry.addInterceptor(clientAuth)
                .addPathPatterns("/api/client/**")
                .excludePathPatterns("/api/client/auth/**");
    }
}
