package com.returensea.gateway.service;

import com.returensea.common.enums.IntentType;

import java.util.Map;
import java.util.Optional;

/**
 * 使用 LLM 对用户消息做意图分类，用于关键词置信度不足时的补充。
 */
public interface LlmIntentClassifier {

    /**
     * 对单条用户消息进行意图分类。
     *
     * @param message 用户输入（原始，无需预先 toLowerCase）
     * @return 分类结果；调用失败或解析失败时返回 empty
     */
    Optional<LlmIntentResult> classify(String message);

    /** LLM 返回的意图分类结果 */
    record LlmIntentResult(IntentType intentType, double confidence, Map<String, Object> entities) {}
}
