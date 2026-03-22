package com.returensea.common.model;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    private String responseId;
    private String traceId;
    private String sessionId;
    private String userId;
    private String content;
    private AgentType agentType;
    private PermissionLevel usedPermission;
    private List<Action> actions;
    private List<Reference> references;
    private Map<String, Object> metadata;
    private String confidence;
    private boolean needsConfirmation;
    private String confirmationPrompt;
    private LocalDateTime timestamp;
    private long processTimeMs;
    private String error;
    /** 可复制的技术详情（异常信息/堆栈等），供调试窗口展示与复制 */
    private String errorDetail;
    /** 本次请求的处理步骤，供前端展示「后台判断过程」 */
    private List<String> processingSteps;
    /** 本轮活动搜索的结构化推荐列表（与 content JSON 中 activities 双写） */
    private List<RecommendedActivity> recommendedActivities;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private String type;
        private String toolName;
        private Map<String, Object> parameters;
        private Object result;
        private boolean success;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reference {
        private String documentId;
        private String title;
        /** 段落 ID（chunkId），用于引用溯源；前端/策略统一使用此字段定位来源段落。 */
        private String chunkId;
        private String content;
        private double score;
    }
}
