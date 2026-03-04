package com.returensea.agent.orchestrator;

import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.AgentResponse;

import java.util.List;
import java.util.function.Consumer;

public interface Orchestrator {
    AgentResponse process(AgentRequest request);

    /**
     * 流式处理：内容片段通过 contentSink 推送，结束时通过 onComplete 回调完整响应（含 processingSteps、traceId 等）。
     */
    void processStream(AgentRequest request, Consumer<String> contentSink, Consumer<AgentResponse> onComplete);

    TaskGraph buildTaskGraph(AgentRequest request);
    AgentResponse executeTaskGraph(TaskGraph taskGraph);
    List<TaskGraph.TaskNode> planExecution(TaskGraph taskGraph);
}
