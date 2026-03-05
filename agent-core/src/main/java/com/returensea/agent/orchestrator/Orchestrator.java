package com.returensea.agent.orchestrator;

import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.AgentResponse;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface Orchestrator {
    AgentResponse process(AgentRequest request);

    /**
     * 流式处理：通过 eventSink(type, payload) 推送事件，type 为 "contentDelta"（正常内容片段）或 "contentBanned"（护栏拦截话术）。
     * 结束时通过 onComplete 回调完整响应（含 processingSteps、traceId 等）。
     */
    void processStream(AgentRequest request, BiConsumer<String, Object> eventSink, Consumer<AgentResponse> onComplete);

    TaskGraph buildTaskGraph(AgentRequest request);
    AgentResponse executeTaskGraph(TaskGraph taskGraph);
    List<TaskGraph.TaskNode> planExecution(TaskGraph taskGraph);
}
