package com.returensea.agent.tool;

import com.returensea.agent.config.ToolCenterConfigProperties;
import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.exception.AgentException;
import com.returensea.common.model.ToolDefinition;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCenterImpl implements ToolCenter {

    private final ToolExecutor toolExecutor;
    private final ToolCenterConfigProperties toolConfig;

    private final Map<String, ToolDefinition> toolRegistry = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (ToolDefinition tool : toolConfig.getListOrDefault()) {
            if (tool.getAllowedAgents() == null) tool.setAllowedAgents(List.of());
            if (tool.getPermissionLevel() == null) tool.setPermissionLevel(PermissionLevel.L0);
            registerTool(tool);
        }
        log.info("Registered {} tools from config", toolRegistry.size());
    }

    @Override
    public void registerTool(ToolDefinition tool) {
        toolRegistry.put(tool.getName(), tool);
    }

    @Override
    public Optional<ToolDefinition> getTool(String toolName) {
        return Optional.ofNullable(toolRegistry.get(toolName));
    }

    @Override
    public List<ToolDefinition> getToolsForAgent(AgentType agentType) {
        return toolRegistry.values().stream()
                .filter(tool -> tool.isEnabled() && tool.getAllowedAgents().contains(agentType.name()))
                .toList();
    }

    @Override
    public List<ToolDefinition> getToolsByPermission(PermissionLevel permissionLevel) {
        return toolRegistry.values().stream()
                .filter(tool -> tool.isEnabled() && 
                        tool.getPermissionLevel().getLevel() <= permissionLevel.getLevel())
                .toList();
    }

    @Override
    public Object executeTool(String toolName, Map<String, Object> params, PermissionLevel currentPermission) {
        ToolDefinition tool = toolRegistry.get(toolName);
        
        if (tool == null) {
            throw AgentException.toolNotFound(toolName);
        }
        
        if (!tool.isEnabled()) {
            throw AgentException.permissionDenied("Tool is disabled: " + toolName);
        }
        
        if (tool.getPermissionLevel().getLevel() > currentPermission.getLevel()) {
            throw AgentException.permissionDenied("Insufficient permission for tool: " + toolName);
        }

        log.info("Executing tool: {} with params: {}", toolName, params);
        
        return toolExecutor.execute(toolName, params);
    }

    @Override
    public boolean isToolAllowed(String toolName, AgentType agentType) {
        ToolDefinition tool = toolRegistry.get(toolName);
        if (tool == null) {
            return false;
        }
        return tool.isEnabled() && tool.getAllowedAgents().contains(agentType.name());
    }

    @Tool("搜索活动，入参可带城市和关键词")
    public String searchActivities(String city, String keyword) {
        Map<String, Object> params = new HashMap<>();
        params.put("city", city == null ? "" : city);
        params.put("keyword", keyword == null ? "" : keyword);
        return String.valueOf(executeTool("searchActivities", params, PermissionLevel.L0));
    }

    @Tool("按活动ID查询活动详情")
    public String getActivityDetail(String activityId) {
        Map<String, Object> params = new HashMap<>();
        params.put("activityId", activityId);
        return String.valueOf(executeTool("getActivityDetail", params, PermissionLevel.L0));
    }

    @Tool("报名活动，需提供活动ID、姓名、手机号")
    public String registerActivity(String activityId, String name, String phone, String email) {
        Map<String, Object> params = new HashMap<>();
        params.put("activityId", activityId);
        params.put("name", name);
        params.put("phone", phone);
        params.put("email", email);
        return String.valueOf(executeTool("registerActivity", params, PermissionLevel.L2));
    }

    @Tool("发起创建活动，需提供标题、城市、日期，可选地点与描述")
    public String createActivity(String title, String city, String date, String location, String description) {
        Map<String, Object> params = new HashMap<>();
        params.put("title", title);
        params.put("city", city);
        params.put("date", date);
        params.put("location", location);
        params.put("description", description);
        return String.valueOf(executeTool("createActivity", params, PermissionLevel.L1));
    }

    @Tool("查询报名订单，入参可为订单号或手机号")
    public String queryOrder(String orderId, String phone) {
        Map<String, Object> params = new HashMap<>();
        params.put("orderId", orderId == null ? "" : orderId);
        params.put("phone", phone == null ? "" : phone);
        return String.valueOf(executeTool("queryOrder", params, PermissionLevel.L1));
    }
}
