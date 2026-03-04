package com.returensea.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 任务图流程模板（简单 DSL）：从 YAML 加载默认步骤及按意图的覆盖模板，
 * Orchestrator 根据 intentType 选择模板生成 TaskGraph，便于编排与导出。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.agent.task-graph")
public class TaskGraphTemplateProperties {

    private static final String FROM_REQUEST = "FROM_REQUEST";

    /** 默认步骤列表，按 order 排序执行 */
    private List<StepDef> steps = defaultSteps();

    /** 按意图的流程覆盖：IntentType.name() -> 步骤列表，未配置的意图使用默认 steps */
    private Map<String, List<StepDef>> templates;

    /** 根据意图取流程步骤：有对应模板用模板，否则用默认 */
    public List<StepDef> getStepsForIntent(String intentType) {
        if (intentType != null && templates != null && templates.containsKey(intentType)) {
            List<StepDef> t = templates.get(intentType);
            if (t != null && !t.isEmpty()) return t;
        }
        return getSteps();
    }

    public List<StepDef> getSteps() {
        return steps != null ? steps : defaultSteps();
    }

    public boolean isFromRequest(String agentType) {
        return FROM_REQUEST.equalsIgnoreCase(agentType);
    }

    @Data
    public static class StepDef {
        private String nodeId;
        private String action;
        /** SYSTEM 或 FROM_REQUEST（表示从请求中取 AgentType） */
        private String agentType;
        private int order;
        private List<String> dependencies = List.of();
    }

    public static List<StepDef> defaultSteps() {
        StepDef intent = new StepDef();
        intent.setNodeId("node-intent");
        intent.setAction("analyze_intent");
        intent.setAgentType("SYSTEM");
        intent.setOrder(0);
        intent.setDependencies(List.of());

        StepDef agent = new StepDef();
        agent.setNodeId("node-agent");
        agent.setAction("process");
        agent.setAgentType(FROM_REQUEST);
        agent.setOrder(1);
        agent.setDependencies(List.of("node-intent"));

        return List.of(intent, agent);
    }
}
