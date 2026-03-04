package com.returensea.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LlmConfig {

    @Value("${ai.agent.llm.provider:ollama}")
    private String provider;

    @Value("${ai.agent.llm.api-key:}")
    private String apiKey;

    @Value("${ai.agent.llm.base-url:}")
    private String baseUrl;

    @Value("${ai.agent.llm.model:}")
    private String modelName;

    @Value("${ai.agent.llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ai.agent.llm.ollama.model:qwen2.5:7b}")
    private String ollamaModelName;

    @Value("${ai.agent.llm.temperature:0.3}")
    private double temperature;

    @Bean
    public ChatModel chatLanguageModel() {
        if ("ollama".equalsIgnoreCase(provider)) {
            return OllamaChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(ollamaModelName)
                    .temperature(temperature)
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        } else {
            return OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(temperature)
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }
    }

    @Bean
    public StreamingChatModel streamingChatLanguageModel() {
        if ("ollama".equalsIgnoreCase(provider)) {
            return OllamaStreamingChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(ollamaModelName)
                    .temperature(temperature)
                    .timeout(Duration.ofSeconds(60))
                    .build();
        } else {
            return OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(temperature)
                    .timeout(Duration.ofSeconds(60))
                    .build();
        }
    }
}
