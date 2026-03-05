package com.returensea.gateway.graph;

/**
 * 意图路由图状态键，与 {@link org.bsc.langgraph4j.state.AgentState} 的 Map 键一致。
 */
public final class IntentRoutingGraphStateKeys {

    public static final String RAW_MESSAGE = "rawMessage";
    public static final String MESSAGE = "message";
    public static final String INTENT_TYPE = "intentType";
    public static final String CONFIDENCE = "confidence";
    public static final String ENTITIES = "entities";
    public static final String TARGET_AGENT = "targetAgent";
    public static final String ROUTE_TYPE = "routeType";
    public static final String REQUIRED_PERMISSION = "requiredPermission";
    public static final String ROUTE_RESULT = "routeResult";

    private IntentRoutingGraphStateKeys() {}
}
