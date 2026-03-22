package com.returensea.gateway.dto;

import com.returensea.common.model.RecommendedActivity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String responseId;
    private String traceId;
    private String content;
    private String agentType;
    private List<String> actions;
    private List<Reference> references;
    private boolean needsConfirmation;
    private String confirmationPrompt;
    private long processTimeMs;
    private LocalDateTime timestamp;
    private String error;
    /** 可复制的技术详情，供调试窗口展示与复制 */
    private String errorDetail;
    /** 处理步骤，用于前端展示后台判断过程 */
    private List<String> processingSteps;
    /** 活动搜索返回的结构化推荐列表（与 agent-core AgentResponse 对齐） */
    private List<RecommendedActivity> recommendedActivities;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reference {
        private String documentId;
        private String title;
        /** 段落 ID（chunkId），用于引用溯源；前端统一使用此字段定位来源段落。 */
        private String chunkId;
        private String content;
    }
}
