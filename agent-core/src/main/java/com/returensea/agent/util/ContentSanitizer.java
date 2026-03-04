package com.returensea.agent.util;

import com.returensea.agent.config.OutputGuardrailsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输出护栏：对 LLM 返回文本做清洗，移除配置的杂散 token（banned-tokens）及正则匹配片段（banned-patterns），
 * 保证返回给前端的内容干净。用于流式与非流式两条路径。
 */
@Component
@RequiredArgsConstructor
public class ContentSanitizer {

    private final OutputGuardrailsProperties outputGuardrails;

    /**
     * 先按禁止词整词移除，再按禁止正则移除匹配片段，最后压缩多余空行。
     */
    public String stripSpuriousTokens(String text) {
        if (text == null) return "";
        String s = stripBannedTokens(text);
        s = stripBannedPatterns(s);
        return normalizeNewlines(s);
    }

    private String stripBannedTokens(String text) {
        List<String> tokens = outputGuardrails.getBannedTokens();
        if (tokens == null || tokens.isEmpty()) return text;
        String s = text;
        for (String token : tokens) {
            if (token == null || token.isEmpty()) continue;
            String escaped = Pattern.quote(token);
            s = s.replaceAll("(?i)\\b" + escaped + "\\b", "");
        }
        return s;
    }

    private String stripBannedPatterns(String text) {
        List<String> patterns = outputGuardrails.getBannedPatterns();
        if (patterns == null || patterns.isEmpty()) return text;
        String s = text;
        for (String regex : patterns) {
            if (regex == null || regex.isEmpty()) continue;
            try {
                s = s.replaceAll(regex, "");
            } catch (Exception ignored) {
                // 配置错误时跳过该条，避免影响主流程
            }
        }
        return s;
    }

    private static String normalizeNewlines(String s) {
        return s.replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").trim();
    }
}
