package com.returensea.gateway.graph;

import com.returensea.gateway.config.GatewayProperties;
import com.returensea.gateway.service.LlmIntentClassifier;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.returensea.gateway.graph.IntentRoutingGraphStateKeys.*;

/**
 * 使用 LangGraph4j 构建意图路由图，暴露 {@link StateGraph}（供 Studio）与 {@link CompiledGraph}（供路由服务）。
 * 流程：START → keyword_classify → [条件] llm_classify | finalize → finalize → END。
 */
@Slf4j
@Configuration
public class IntentRoutingGraphConfig {

    private static final String NODE_KEYWORD_CLASSIFY = "keyword_classify";
    private static final String NODE_LLM_CLASSIFY = "llm_classify";
    private static final String NODE_FINALIZE = "finalize";

    @Bean
    public StateGraph<AgentState> intentRoutingStateGraph(
            GatewayProperties gatewayProperties,
            IntentRoutingGraphNodes graphNodes,
            @Autowired(required = false) LlmIntentClassifier llmIntentClassifier
    ) throws GraphStateException {
        double threshold = gatewayProperties.getIntent() != null
                ? gatewayProperties.getIntent().getLowConfidenceThreshold()
                : 0.6;
        boolean llmEnabled = gatewayProperties.getIntent() != null
                && gatewayProperties.getIntent().getLlm() != null
                && gatewayProperties.getIntent().getLlm().isEnabled();

        AgentStateFactory<AgentState> stateFactory = map -> new AgentState((Map<String, Object>) map);
        StateGraph<AgentState> graph = new StateGraph<>(stateFactory);

        AsyncNodeAction<AgentState> keywordClassifyAction = state ->
                CompletableFuture.completedFuture(graphNodes.keywordClassify(state));

        AsyncNodeAction<AgentState> llmClassifyAction = state ->
                CompletableFuture.completedFuture(graphNodes.llmClassify(state, llmIntentClassifier));

        AsyncNodeAction<AgentState> finalizeAction = state ->
                CompletableFuture.completedFuture(graphNodes.finalize(state, threshold));

        graph.addNode(NODE_KEYWORD_CLASSIFY, keywordClassifyAction);
        graph.addNode(NODE_LLM_CLASSIFY, llmClassifyAction);
        graph.addNode(NODE_FINALIZE, finalizeAction);

        graph.addEdge(GraphDefinition.START, NODE_KEYWORD_CLASSIFY);

        graph.addConditionalEdges(
                NODE_KEYWORD_CLASSIFY,
                (state, config) -> {
                    double confidence = state.value(CONFIDENCE).map(o -> (Double) o).orElse(0.0);
                    boolean useLlm = confidence < threshold && llmEnabled && llmIntentClassifier != null;
                    String next = useLlm ? NODE_LLM_CLASSIFY : NODE_FINALIZE;
                    return CompletableFuture.completedFuture(new Command(next, Map.of()));
                },
                Map.of(NODE_LLM_CLASSIFY, NODE_LLM_CLASSIFY, NODE_FINALIZE, NODE_FINALIZE)
        );

        graph.addEdge(NODE_LLM_CLASSIFY, NODE_FINALIZE);
        graph.addEdge(NODE_FINALIZE, GraphDefinition.END);

        return graph;
    }

    @Bean
    public CompiledGraph<AgentState> intentRoutingGraph(StateGraph<AgentState> intentRoutingStateGraph) throws GraphStateException {
        CompiledGraph<AgentState> compiled = intentRoutingStateGraph.compile();
        log.info("Intent routing graph compiled (LangGraph4j): keyword_classify → [llm_classify?] → finalize");
        return compiled;
    }
}
