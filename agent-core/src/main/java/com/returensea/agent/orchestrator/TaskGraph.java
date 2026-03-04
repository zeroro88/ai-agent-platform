package com.returensea.agent.orchestrator;

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
public class TaskGraph {
    private String taskId;
    private String sessionId;
    private String userId;
    private String originalMessage;
    private TaskNode rootNode;
    private List<TaskNode> allNodes;
    private Map<String, Object> context;
    private TaskStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskNode {
        private String nodeId;
        private String agentType;
        private String action;
        private Map<String, Object> params;
        private Map<String, Object> result;
        private NodeStatus status;
        private List<String> dependencies;
        private int order;

        public enum NodeStatus {
            PENDING,
            RUNNING,
            COMPLETED,
            FAILED,
            SKIPPED
        }
    }
}
