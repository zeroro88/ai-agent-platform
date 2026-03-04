package com.returensea.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出护栏配置：禁止出现在 LLM 返回内容中的 token/词（denylist）及正则模式，
 * 用于过滤多语言模型偶现的杂散外文词、英文结尾语等。与业界 Guardrails 的 ban_list 思路一致。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.agent.output-guardrails")
public class OutputGuardrailsProperties {

    /** 禁止词列表，整词匹配（不区分大小写），匹配到的会被移除 */
    private List<String> bannedTokens = new ArrayList<>();

    /** 禁止的正则模式列表，匹配到的片段会被移除（如英文 sign-off 整句） */
    private List<String> bannedPatterns = new ArrayList<>();
}
