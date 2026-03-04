package com.returensea.agent.config;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.model.ToolDefinition;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 工具列表与权限：从 YAML 加载 name、description、permissionLevel、allowedAgents、enabled，
 * 启动时注册到 ToolCenter，便于修改与扩展。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.agent.tools")
public class ToolCenterConfigProperties {

    private List<ToolDefinition> list;

    /** 配置为空或未提供时返回默认工具列表，保证与原有行为一致 */
    public List<ToolDefinition> getListOrDefault() {
        if (list != null && !list.isEmpty()) {
            return list;
        }
        return defaultTools();
    }

    private static List<ToolDefinition> defaultTools() {
        return List.of(
                ToolDefinition.builder().name("searchActivities").description("搜索活动列表").permissionLevel(PermissionLevel.L0).allowedAgents(List.of(AgentType.ACTIVITY.name())).enabled(true).build(),
                ToolDefinition.builder().name("getActivityDetail").description("获取活动详情").permissionLevel(PermissionLevel.L0).allowedAgents(List.of(AgentType.ACTIVITY.name())).enabled(true).build(),
                ToolDefinition.builder().name("registerActivity").description("报名活动").permissionLevel(PermissionLevel.L2).allowedAgents(List.of(AgentType.ACTIVITY.name())).enabled(true).build(),
                ToolDefinition.builder().name("createActivity").description("发起创建活动").permissionLevel(PermissionLevel.L1).allowedAgents(List.of(AgentType.ACTIVITY.name())).enabled(true).build(),
                ToolDefinition.builder().name("queryOrder").description("查询订单状态").permissionLevel(PermissionLevel.L1).allowedAgents(List.of(AgentType.ACTIVITY.name())).enabled(true).build(),
                ToolDefinition.builder().name("searchPolicy").description("搜索政策文档").permissionLevel(PermissionLevel.L0).allowedAgents(List.of(AgentType.POLICY.name())).enabled(true).build(),
                ToolDefinition.builder().name("subscribePolicy").description("订阅政策更新").permissionLevel(PermissionLevel.L1).allowedAgents(List.of(AgentType.POLICY.name())).enabled(true).build(),
                ToolDefinition.builder().name("processPayment").description("处理支付").permissionLevel(PermissionLevel.L3).allowedAgents(List.of()).enabled(false).build(),
                ToolDefinition.builder().name("refund").description("退款操作").permissionLevel(PermissionLevel.L3).allowedAgents(List.of()).enabled(false).build()
        );
    }
}
