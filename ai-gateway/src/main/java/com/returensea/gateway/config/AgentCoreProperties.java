package com.returensea.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "agent-core")
public class AgentCoreProperties {
    private String baseUrl = "http://localhost:8080";
    private int connectTimeout = 5000;
    /** 同步调用 agent-core /process 的最大等待（毫秒）；默认与 e2e HTTP 读超时一致，避免慢 LLM 30s 即失败 */
    private int readTimeout = 180000;

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
