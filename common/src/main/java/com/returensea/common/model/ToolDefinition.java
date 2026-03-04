package com.returensea.common.model;

import com.returensea.common.enums.PermissionLevel;
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
public class ToolDefinition {
    private String name;
    private String description;
    private PermissionLevel permissionLevel;
    private Map<String, ToolParam> parameters;
    private String returnType;
    private List<String> allowedAgents;
    private boolean enabled;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolParam {
        private String name;
        private String description;
        private String type;
        private boolean required;
        private Object defaultValue;
    }
}
