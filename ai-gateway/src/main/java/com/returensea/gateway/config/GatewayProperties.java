package com.returensea.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.gateway")
public class GatewayProperties {
    private RouteConfig route = new RouteConfig();
    private IntentConfig intent = new IntentConfig();
    private RateLimitConfig rateLimit = new RateLimitConfig();
    private AuthConfig auth = new AuthConfig();
    private List<String> allowedOrigins;
    private Map<String, String> llmProviders;

    @Data
    public static class RouteConfig {
        private String defaultService;
        private Map<String, String> serviceUrls;
        private int timeoutMs = 30000;
        private boolean enableFallback = true;
    }

    @Data
    public static class IntentConfig {
        private double lowConfidenceThreshold = 0.6;
        /** 低置信度时是否用 LLM 做意图分类；未配置或 false 则仅关键词 */
        private LlmConfig llm = new LlmConfig();

        @Data
        public static class LlmConfig {
            private boolean enabled = false;
            private String provider = "ollama";
            private String apiKey = "";
            private String baseUrl = "";
            private String model = "";
            private String ollamaBaseUrl = "http://localhost:11434";
            private String ollamaModel = "qwen2.5:7b";
            private double temperature = 0.1;
        }
    }

    @Data
    public static class RateLimitConfig {
        private boolean enabled = true;
        private int requestsPerMinute = 60;
        private int burstCapacity = 10;
    }

    @Data
    public static class AuthConfig {
        private boolean enabled = true;
        private String jwtSecret;
        private long tokenExpiryMinutes = 60;
    }
}
