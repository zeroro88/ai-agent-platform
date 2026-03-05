package com.returensea.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.common.enums.IntentType;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@ConditionalOnBean(name = "intentChatModel")
public class LlmIntentClassifierImpl implements LlmIntentClassifier {

    private static final String SYSTEM_PROMPT = """
        你是海归服务平台的意图分类器。根据用户一句话，判断其意图。
        只输出一个 JSON 对象，不要其他文字。格式：{"intent":"<枚举名>","confidence":<0-1 小数>,"city":"<仅当用户提到城市时填写>"}
        意图枚举名必须为以下之一（严格按大小写）：
        POLICY_CONSULT, ACTIVITY_QUERY, ACTIVITY_CREATE, ACTIVITY_REGISTER, ORDER_QUERY, USER_PROFILE, RECOMMEND, CHITCHAT, UNKNOWN
        含义：政策咨询、活动查询、创建活动、活动报名、订单查询、用户资料、个性化推荐、闲聊、未知。
        """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmIntentClassifierImpl(@Qualifier("intentChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Optional<LlmIntentResult> classify(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        try {
            String userPrompt = "用户说：" + message;
            String response = chatModel.chat(SYSTEM_PROMPT + "\n\n" + userPrompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("LLM intent classification failed for message length={}: {}", message.length(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<LlmIntentResult> parseResponse(String response) {
        if (response == null || response.isBlank()) return Optional.empty();
        String json = response.trim();
        // 允许被 markdown 代码块包裹
        if (json.startsWith("```")) {
            int start = json.indexOf('\n') + 1;
            int end = json.lastIndexOf("```");
            if (end > start) json = json.substring(start, end).trim();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String intentStr = root.has("intent") ? root.get("intent").asText().trim() : null;
            double confidence = root.has("confidence") ? root.get("confidence").asDouble(0.7) : 0.7;
            Map<String, Object> entities = new HashMap<>();
            if (root.has("city") && !root.get("city").isNull()) {
                String city = root.get("city").asText();
                if (!city.isBlank()) entities.put("city", city);
            }
            IntentType intentType = parseIntentType(intentStr);
            return Optional.of(new LlmIntentResult(intentType, Math.min(1, Math.max(0, confidence)), entities));
        } catch (Exception e) {
            log.debug("Failed to parse LLM intent response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static IntentType parseIntentType(String intentStr) {
        if (intentStr == null || intentStr.isBlank()) return IntentType.UNKNOWN;
        try {
            return IntentType.valueOf(intentStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IntentType.UNKNOWN;
        }
    }
}
