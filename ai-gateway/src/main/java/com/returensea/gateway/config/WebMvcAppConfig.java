package com.returensea.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 保证 /app 与 /app/ 能打开调试页（static/app/index.html）。
 * 将 /app、/app/ 重定向到 /app/index.html，避免 404。
 */
@Configuration
public class WebMvcAppConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/index.html");
        registry.addRedirectViewController("/app/", "/app/index.html");
    }
}
