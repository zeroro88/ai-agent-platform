package com.returensea.agent.recommend;

import java.util.List;

/**
 * 活动推荐精排：在「结构化召回」得到的候选活动列表内，用 LLM 做排序并生成推荐理由。
 * 约束：仅允许返回候选集内的活动 ID，禁止幻觉。
 */
public interface ActivityRerankService {

    /**
     * 在候选活动内精排并生成推荐理由。
     *
     * @param candidates 结构化召回得到的候选活动（仅此列表内的 ID 可出现在结果中）
     * @param userContext 用户上下文（如城市、关键词、简短需求描述）
     * @param topK 返回前 K 条
     * @return 按相关性排序的 (id, reason)，仅包含候选集内的 ID；失败或空候选时返回空列表
     */
    List<RerankedActivity> rerankWithReasons(List<ActivityCandidate> candidates, String userContext, int topK);

    record ActivityCandidate(String id, String title, String city, String description) {}

    record RerankedActivity(String id, String reason) {}
}
