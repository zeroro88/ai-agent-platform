package com.returensea.gateway.service;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.IntentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.enums.RouteType;
import com.returensea.gateway.config.GatewayProperties;
import com.returensea.gateway.config.IntentRoutingProperties;
import com.returensea.gateway.dto.ChatRequest;
import com.returensea.gateway.dto.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouterServiceImpl implements IntentRouterService {

    private final GatewayProperties properties;
    private final IntentRoutingProperties routingRules;

    @Autowired(required = false)
    private LlmIntentClassifier llmIntentClassifier;

    @Override
    public RouteResult route(ChatRequest request) {
        String rawMessage = request.getMessage();
        String message = rawMessage.toLowerCase();

        double threshold = properties.getIntent() != null ? properties.getIntent().getLowConfidenceThreshold() : 0.6;

        // 1) 关键词路径
        IntentType intentType = classifyIntent(message);
        AgentType targetAgent = determineTargetAgent(intentType);
        PermissionLevel requiredPermission = determinePermissionLevel(message);
        RouteType routeType = determineRouteType(requiredPermission);
        Map<String, Object> entities = extractEntities(message, intentType);
        double confidence = calculateConfidence(message, intentType);

        // 2) 低置信度且启用 LLM 时，用 LLM 再分类一次
        if (confidence < threshold && llmIntentClassifier != null) {
            Optional<LlmIntentClassifier.LlmIntentResult> llmResult = llmIntentClassifier.classify(rawMessage);
            if (llmResult.isPresent()) {
                LlmIntentClassifier.LlmIntentResult r = llmResult.get();
                intentType = r.intentType();
                confidence = r.confidence();
                if (r.entities() != null && !r.entities().isEmpty()) {
                    entities = new HashMap<>(r.entities());
                }
                targetAgent = determineTargetAgent(intentType);
                log.debug("LLM intent override: intent={}, confidence={}", intentType, confidence);
            }
        }

        boolean needsClarification = confidence < threshold;
        return RouteResult.builder()
                .intentType(intentType)
                .targetAgent(targetAgent)
                .routeType(routeType)
                .requiredPermission(requiredPermission)
                .extractedEntities(entities)
                .confidence(confidence)
                .needsClarification(needsClarification)
                .clarification(needsClarification ? "我需要确认您的具体需求" : null)
                .build();
    }

    @Override
    public boolean needsClarification(RouteResult result) {
        return result.isNeedsClarification();
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

    private boolean containsAny(String message, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return false;
        return keywords.stream().anyMatch(message::contains);
    }
}
