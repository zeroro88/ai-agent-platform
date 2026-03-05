package com.returensea.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 输入护栏配置：禁止用户输入中出现的词或正则模式（如敏感词、常见注入前缀），
 * 与 LangChain4j InputGuardrail 配合，在调用 LLM 前拦截。
 * 支持从文件加载词条（banned-tokens-file），可与开源词库如 Sensitive-lexicon 配合使用。
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.agent.input-guardrails")
public class InputGuardrailsProperties {

    private final ResourceLoader resourceLoader;

    /** 是否启用输入护栏 */
    private boolean enabled = true;

    /** 禁止词列表，整词匹配（不区分大小写），命中则拦截不调 LLM。可与 banned-tokens-file 合并。 */
    private List<String> bannedTokens = new ArrayList<>();

    /**
     * 禁止词文件路径（可选）。每行一词，与 banned-tokens 合并去重。
     * 支持 classpath:xxx 或 file:xxx，例如 classpath:sensitive-words.txt
     */
    private String bannedTokensFile = "";

    /** 禁止的正则模式，命中则拦截 */
    private List<String> bannedPatterns = new ArrayList<>();

    /** 拦截时返回给用户的固定话术 */
    private String blockMessage = "您的问题涉及敏感或违规内容，请换一种方式提问。";

    /** 用于测试或非 Spring 环境：不注入 ResourceLoader 时不从文件加载 */
    public InputGuardrailsProperties() {
        this.resourceLoader = null;
    }

    public InputGuardrailsProperties(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void loadBannedTokensFromFile() {
        if (resourceLoader == null || bannedTokensFile == null || bannedTokensFile.isBlank()) return;
        String path = bannedTokensFile;
        try {
            Resource resource = resourceLoader.getResource(Objects.requireNonNull(path, "bannedTokensFile"));
            if (!resource.exists()) {
                log.warn("Input guardrails banned-tokens-file not found: {}", bannedTokensFile);
                return;
            }
            List<String> fromFile = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        fromFile.add(trimmed);
                    }
                }
            }
            if (!fromFile.isEmpty()) {
                int fromConfig = bannedTokens.size();
                List<String> merged = new ArrayList<>(bannedTokens);
                for (String t : fromFile) {
                    if (!merged.contains(t)) merged.add(t);
                }
                this.bannedTokens = merged;
                log.info("Input guardrails: {} from config + {} from file => {} total", fromConfig, fromFile.size(), merged.size());
            }
        } catch (Exception e) {
            log.error("Failed to load banned-tokens-file: {}", bannedTokensFile, e);
        }
    }
}
