package com.returensea.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    private final AgentCoreProperties agentCoreProperties;

    public WebClientConfig(AgentCoreProperties agentCoreProperties) {
        this.agentCoreProperties = agentCoreProperties;
    }

    @Bean
    public WebClient agentCoreWebClient() {
        return WebClient.builder()
                .baseUrl(agentCoreProperties.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
