package com.returensea.gateway.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 意图分类用 LLM：仅在 ai.gateway.intent.llm.enabled=true 时创建。
 * 与 agent-core 的 LLM 独立配置，便于 gateway 单独使用小模型或不同 endpoint。
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.gateway.intent.llm", name = "enabled", havingValue = "true")
public class IntentLlmConfig {

    @Bean(name = "intentChatModel")
    public ChatModel intentChatModel(GatewayProperties gatewayProperties) {
        GatewayProperties.IntentConfig.LlmConfig llm = gatewayProperties.getIntent().getLlm();
        if ("ollama".equalsIgnoreCase(llm.getProvider())) {
            return OllamaChatModel.builder()
                    .baseUrl(llm.getOllamaBaseUrl() != null ? llm.getOllamaBaseUrl() : "http://localhost:11434")
                    .modelName(llm.getOllamaModel() != null ? llm.getOllamaModel() : "qwen2.5:7b")
                    .temperature(llm.getTemperature())
                    .timeout(Duration.ofSeconds(30))
                    .build();
        }
        return OpenAiChatModel.builder()
                .apiKey(llm.getApiKey() != null ? llm.getApiKey() : "")
                .baseUrl(llm.getBaseUrl() != null && !llm.getBaseUrl().isEmpty() ? llm.getBaseUrl() : "https://api.openai.com/v1")
                .modelName(llm.getModel() != null && !llm.getModel().isEmpty() ? llm.getModel() : "gpt-3.5-turbo")
                .temperature(llm.getTemperature())
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}
