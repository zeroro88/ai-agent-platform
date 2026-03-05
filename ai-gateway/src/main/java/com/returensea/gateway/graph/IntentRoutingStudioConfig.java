package com.returensea.gateway.graph;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.studio.LangGraphStudioServer;
import org.bsc.langgraph4j.studio.springboot.LangGraphStudioConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * 注册意图路由图到 LangGraph4j Studio，便于在浏览器中查看图结构、执行并查看每步状态。
 * 仅当 profile=middleware 时启用（与 application-middleware.yml 一致）
 * 启动后访问：http://localhost:&lt;port&gt;/ 并选择 instance=intent-routing，或直接打开 ?instance=intent-routing
 */
@Configuration
@Profile("middleware")
public class IntentRoutingStudioConfig extends LangGraphStudioConfig {

    private final StateGraph<AgentState> intentRoutingStateGraph;

    public IntentRoutingStudioConfig(StateGraph<AgentState> intentRoutingStateGraph) {
        this.intentRoutingStateGraph = intentRoutingStateGraph;
    }

    @Override
    public Map<String, LangGraphStudioServer.Instance> instanceMap() {
        var saver = new MemorySaver();
        var compileConfig = CompileConfig.builder()
                .checkpointSaver(saver)
                .build();
        var instance = LangGraphStudioServer.Instance.builder()
                .title("Intent Routing")
                .graph(intentRoutingStateGraph)
                .compileConfig(compileConfig)
                .addInputStringArg(IntentRoutingGraphStateKeys.RAW_MESSAGE)
                .addInputStringArg(IntentRoutingGraphStateKeys.MESSAGE)
                .build();
        return Map.of("intent-routing", instance);
    }
}
