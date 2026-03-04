package com.returensea.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "agent-core")
public class AgentCoreProperties {
    private String baseUrl = "http://localhost:8081";
    private int connectTimeout = 5000;
    private int readTimeout = 30000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }
}
