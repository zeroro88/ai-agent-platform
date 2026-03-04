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
public class AgentRequest {
    private String sessionId;
    private String userId;
    private String message;
    private AgentType agentType;
    private PermissionLevel requiredPermission;
    private Map<String, Object> context;
    private List<Slot> slots;
    private LocalDateTime timestamp;
    private String traceId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Slot {
        private String name;
        private String description;
        private boolean required;
        private Object value;
        private String status;
    }
}
