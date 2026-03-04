package com.returensea.common.model;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.IntentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.enums.RouteType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentRouteResult {
    private IntentType intentType;
    private AgentType targetAgent;
    private RouteType routeType;
    private PermissionLevel requiredPermission;
    private Map<String, Object> extractedEntities;
    private List<SlotInfo> slots;
    private double confidence;
    private String clarification;
    private boolean needsClarification;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlotInfo {
        private String name;
        private String description;
        private boolean required;
        private Object value;
        private String source;
    }
}
