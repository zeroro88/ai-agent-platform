package com.returensea.gateway.service;

import com.returensea.gateway.dto.ChatRequest;
import com.returensea.gateway.dto.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

import static com.returensea.gateway.graph.IntentRoutingGraphStateKeys.*;

/**
 * 意图路由服务：通过 LangGraph4j 编译图执行 keyword_classify → [llm_classify?] → finalize，返回 {@link RouteResult}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouterServiceImpl implements IntentRouterService {

    private final CompiledGraph<AgentState> intentRoutingGraph;

    @Override
    public RouteResult route(ChatRequest request) {
        String rawMessage = request.getMessage() != null ? request.getMessage() : "";
        String message = rawMessage.toLowerCase();

        Map<String, Object> initialState = Map.of(
                RAW_MESSAGE, rawMessage,
                MESSAGE, message
        );

        Optional<AgentState> finalState = intentRoutingGraph.invoke(initialState);
        return finalState
                .flatMap(state -> state.value(ROUTE_RESULT).map(r -> (RouteResult) r))
                .orElseGet(() -> {
                    log.warn("Intent routing graph returned empty; building fallback RouteResult");
                    return RouteResult.builder()
                            .needsClarification(true)
                            .clarification("我需要确认您的具体需求")
                            .build();
                });
    }

    @Override
    public boolean needsClarification(RouteResult result) {
        return result != null && result.isNeedsClarification();
    }
}
