package com.returensea.agent.util;

import java.util.List;

/**
 * 判断用户是否在「发现/列举活动」语境中，用于在不调 LLM 工具的情况下前置执行 searchActivities。
 */
public final class ActivityDiscoveryHeuristics {

    private static final List<String> ACTIVITY_TOPICS = List.of(
            "活动", "讲座", "沙龙", "聚会", "会议", "比赛");

    private ActivityDiscoveryHeuristics() {
    }

    public static boolean looksLikeActivityDiscovery(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        boolean topic = ACTIVITY_TOPICS.stream().anyMatch(message::contains);
        if (!topic) {
            return false;
        }
        if (message.contains("最近")) {
            return true;
        }
        if (message.contains("找") && message.contains("活动")) {
            return true;
        }
        return message.contains("推荐")
                || message.contains("有什么")
                || message.contains("有哪些")
                || message.contains("搜")
                || message.contains("看看")
                || message.contains("列出")
                || message.contains("帮我找")
                || message.contains("想找")
                || message.contains("查一下");
    }
}
