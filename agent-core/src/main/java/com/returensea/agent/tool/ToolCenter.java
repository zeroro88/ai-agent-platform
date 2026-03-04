package com.returensea.agent.tool;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.model.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ToolCenter {
    void registerTool(ToolDefinition tool);
    Optional<ToolDefinition> getTool(String toolName);
    List<ToolDefinition> getToolsForAgent(AgentType agentType);
    List<ToolDefinition> getToolsByPermission(PermissionLevel permissionLevel);
    Object executeTool(String toolName, Map<String, Object> params, PermissionLevel currentPermission);
    boolean isToolAllowed(String toolName, AgentType agentType);
}
