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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BannedContentInputGuardrail implements InputGuardrail {

    private final InputGuardrailsProperties properties;

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        if (!properties.isEnabled()) {
            return success();
        }
        String text = userMessage.singleText();
        if (text == null) text = "";

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
}
