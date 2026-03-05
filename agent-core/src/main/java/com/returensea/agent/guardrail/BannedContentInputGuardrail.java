package com.returensea.agent.guardrail;

import com.returensea.agent.config.InputGuardrailsProperties;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入护栏：根据配置的禁止词与正则校验用户消息，命中则拦截，不调用 LLM。
 * 若消息包含「当前用户输入：」则仅校验该标记之后的当轮用户输入，避免历史对话中的词触发误判。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BannedContentInputGuardrail implements InputGuardrail {

    private static final String CURRENT_INPUT_PREFIX = "当前用户输入：";

    private final InputGuardrailsProperties properties;

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        if (!properties.isEnabled()) {
            return success();
        }
        String fullText = userMessage.singleText();
        if (fullText == null) fullText = "";
        // 仅校验当轮用户输入，避免「最近对话」等上下文中的词误判（如历史里出现过「色情」导致「我想报名活动」被拦）
        String text = extractCurrentUserInput(fullText);

        // 子串匹配：Java 的 \b 只认 [a-zA-Z0-9_]，中文等会匹配不到，故统一用子串匹配
        List<String> tokens = properties.getBannedTokens();
        if (tokens != null) {
            for (String token : tokens) {
                if (token == null || token.isEmpty()) continue;
                String escaped = Pattern.quote(token);
                if (Pattern.compile(escaped, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                    log.warn("Input guardrail blocked (banned token): {}", token);
                    return fatal(properties.getBlockMessage());
                }
            }
        }

        List<String> patterns = properties.getBannedPatterns();
        if (patterns != null) {
            for (String regex : patterns) {
                if (regex == null || regex.isEmpty()) continue;
                try {
                    if (Pattern.compile(regex).matcher(text).find()) {
                        log.warn("Input guardrail blocked (banned pattern): {}", regex);
                        return fatal(properties.getBlockMessage());
                    }
                } catch (Exception e) {
                    log.debug("Invalid input guardrail pattern, skip: {}", regex);
                }
            }
        }

        return success();
    }

    /** 若存在「当前用户输入：」则只返回该标记之后的文本，否则返回全文（兼容直接传 raw 的场景）。 */
    private static String extractCurrentUserInput(String fullText) {
        int idx = fullText.lastIndexOf(CURRENT_INPUT_PREFIX);
        if (idx >= 0 && idx + CURRENT_INPUT_PREFIX.length() <= fullText.length()) {
            return fullText.substring(idx + CURRENT_INPUT_PREFIX.length()).trim();
        }
        return fullText;
    }
}
