package com.returensea.gateway.graph;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.IntentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.enums.RouteType;
import com.returensea.gateway.config.IntentRoutingProperties;
import com.returensea.gateway.dto.RouteResult;
import com.returensea.gateway.service.LlmIntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.returensea.gateway.graph.IntentRoutingGraphStateKeys.*;

/**
 * 意图路由图节点逻辑：关键词分类、LLM 分类、最终路由结果汇总。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRoutingGraphNodes {

    private final IntentRoutingProperties routingRules;

    /**
     * 节点：关键词意图分类，写入 intentType、confidence、entities。
     */
    public Map<String, Object> keywordClassify(AgentState state) {
        String message = state.value(MESSAGE).orElse("").toString();
        IntentType intentType = classifyIntent(message);
        Map<String, Object> entities = extractEntities(message, intentType);
        double confidence = calculateConfidence(message, intentType);

        Map<String, Object> update = new HashMap<>();
        update.put(INTENT_TYPE, intentType);
        update.put(CONFIDENCE, confidence);
        update.put(ENTITIES, entities);
        return update;
    }

    /**
     * 节点：LLM 意图分类（低置信度时），覆盖 intentType、confidence、entities。
     */
    public Map<String, Object> llmClassify(AgentState state, LlmIntentClassifier llmClassifier) {
        if (llmClassifier == null) {
            return Map.of();
        }
        String rawMessage = state.value(RAW_MESSAGE).map(Object::toString).orElse("");
        Optional<LlmIntentClassifier.LlmIntentResult> result = llmClassifier.classify(rawMessage);
        if (result.isEmpty()) {
            return Map.of();
        }
        LlmIntentClassifier.LlmIntentResult r = result.get();
        Map<String, Object> update = new HashMap<>();
        update.put(INTENT_TYPE, r.intentType());
        update.put(CONFIDENCE, r.confidence());
        if (r.entities() != null && !r.entities().isEmpty()) {
            update.put(ENTITIES, new HashMap<>(r.entities()));
        }
        log.debug("LLM intent override: intent={}, confidence={}", r.intentType(), r.confidence());
        return update;
    }

    /**
     * 节点：汇总路由结果，写入 routeResult。
     */
    public Map<String, Object> finalize(AgentState state, double threshold) {
        String message = state.value(MESSAGE).orElse("").toString();
        IntentType intentType = state.value(INTENT_TYPE).map(o -> (IntentType) o).orElse(IntentType.CHITCHAT);
        double confidence = state.value(CONFIDENCE).map(o -> (Double) o).orElse(0.7);
        @SuppressWarnings("unchecked")
        Map<String, Object> entities = state.value(ENTITIES).map(o -> (Map<String, Object>) o).orElse(new HashMap<>());

        AgentType targetAgent = determineTargetAgent(intentType);
        PermissionLevel requiredPermission = determinePermissionLevel(message);
        RouteType routeType = determineRouteType(requiredPermission);
        boolean needsClarification = confidence < threshold;

        RouteResult routeResult = RouteResult.builder()
                .intentType(intentType)
                .targetAgent(targetAgent)
                .routeType(routeType)
                .requiredPermission(requiredPermission)
                .extractedEntities(entities)
                .confidence(confidence)
                .needsClarification(needsClarification)
                .clarification(needsClarification ? "我需要确认您的具体需求" : null)
                .build();

        return Map.of(ROUTE_RESULT, routeResult);
    }

    private IntentType classifyIntent(String message) {
        if (containsAny(message, routingRules.getIntentKeywords("policy"))) {
            return IntentType.POLICY_CONSULT;
        }
        if (containsAny(message, routingRules.getIntentKeywords("activity-create"))) {
            return IntentType.ACTIVITY_CREATE;
        }
        List<String> activityKw = routingRules.getIntentKeywords("activity");
        if (containsAny(message, activityKw)) {
            if (containsAny(message, routingRules.getPermissionKeywords("L2")) || containsAny(message, routingRules.getPermissionKeywords("L3"))) {
                return IntentType.ACTIVITY_REGISTER;
            }
            return IntentType.ACTIVITY_QUERY;
        }
        if (containsAny(message, routingRules.getIntentKeywords("order"))) {
            return IntentType.ORDER_QUERY;
        }
        if (containsAny(message, routingRules.getIntentKeywords("user-profile"))) {
            return IntentType.USER_PROFILE;
        }
        if (containsAny(message, routingRules.getIntentKeywords("recommend"))) {
            return IntentType.RECOMMEND;
        }
        return IntentType.CHITCHAT;
    }

    private AgentType determineTargetAgent(IntentType intentType) {
        String agentName = routingRules.getAgentForIntent(intentType.name());
        try {
            return AgentType.valueOf(agentName);
        } catch (Exception e) {
            return AgentType.ACTIVITY;
        }
    }

    private PermissionLevel determinePermissionLevel(String message) {
        if (containsAny(message, routingRules.getPermissionKeywords("L3"))) {
            return PermissionLevel.L3;
        }
        if (containsAny(message, routingRules.getPermissionKeywords("L2"))) {
            return PermissionLevel.L2;
        }
        if (containsAny(message, routingRules.getPermissionKeywords("L1"))) {
            return PermissionLevel.L1;
        }
        if (containsAny(message, routingRules.getPermissionKeywords("L0"))) {
            return PermissionLevel.L0;
        }
        return PermissionLevel.L0;
    }

    private RouteType determineRouteType(PermissionLevel permission) {
        if (permission.isForbidden()) {
            return RouteType.SLOW_TRACK;
        }
        if (permission.requiresConfirmation()) {
            return RouteType.HYBRID;
        }
        return RouteType.FAST_TRACK;
    }

    private Map<String, Object> extractEntities(String message, IntentType intentType) {
        Map<String, Object> entities = new HashMap<>();
        for (String city : routingRules.getEntityCities()) {
            if (message.contains(city)) {
                entities.put("city", city);
                break;
            }
        }
        return entities;
    }

    private double calculateConfidence(String message, IntentType intentType) {
        double baseConfidence = 0.7;
        if (containsAny(message, routingRules.getIntentKeywords("policy")) && intentType == IntentType.POLICY_CONSULT) {
            baseConfidence += routingRules.getConfidenceBoost("policy");
        }
        if (containsAny(message, routingRules.getIntentKeywords("activity")) && intentType == IntentType.ACTIVITY_QUERY) {
            baseConfidence += routingRules.getConfidenceBoost("activity-query");
        }
        if (containsAny(message, routingRules.getIntentKeywords("activity-create")) && intentType == IntentType.ACTIVITY_CREATE) {
            baseConfidence += routingRules.getConfidenceBoost("activity-create");
        }
        return Math.min(baseConfidence, 0.95);
    }

    private static boolean containsAny(String message, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return false;
        return keywords.stream().anyMatch(message::contains);
    }
}
