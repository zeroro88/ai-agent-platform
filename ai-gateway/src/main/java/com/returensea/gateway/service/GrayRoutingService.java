package com.returensea.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class GrayRoutingService {

    private final Map<String, GrayRule> rules = new ConcurrentHashMap<>();
    private final Map<String, Integer> userBuckets = new ConcurrentHashMap<>();

    public GrayRoutingService() {
        initializeDefaultRules();
    }

    private void initializeDefaultRules() {
        registerRule(GrayRule.builder()
            .featureId("new-recommendation")
            .enabled(true)
            .percentage(10)
            .targetUsers(Set.of())
            .excludeUsers(Set.of())
            .build());
        
        registerRule(GrayRule.builder()
            .featureId("slot-filling")
            .enabled(true)
            .percentage(20)
            .targetUsers(Set.of())
            .excludeUsers(Set.of())
            .build());
        
        registerRule(GrayRule.builder()
            .featureId("vector-memory")
            .enabled(true)
            .percentage(15)
            .targetUsers(Set.of())
            .excludeUsers(Set.of())
            .build());
    }

    public boolean isFeatureEnabled(String userId, String featureId) {
        GrayRule rule = rules.get(featureId);
        
        if (rule == null || !rule.enabled()) {
            return false;
        }
        
        if (rule.targetUsers().contains(userId)) {
            return true;
        }
        
        if (rule.excludeUsers().contains(userId)) {
            return false;
        }
        
        int bucket = getUserBucket(userId, featureId);
        return bucket < rule.percentage();
    }

    public void registerRule(GrayRule rule) {
        rules.put(rule.featureId(), rule);
        log.info("Registered gray rule: {} (enabled={}, percentage={}%)", 
            rule.featureId(), rule.enabled(), rule.percentage());
    }

    public void updateRulePercentage(String featureId, int percentage) {
        GrayRule existing = rules.get(featureId);
        if (existing != null) {
            registerRule(GrayRule.builder()
                .featureId(featureId)
                .enabled(existing.enabled())
                .percentage(percentage)
                .targetUsers(existing.targetUsers())
                .excludeUsers(existing.excludeUsers())
                .build());
        }
    }

    public void addTargetUser(String featureId, String userId) {
        GrayRule existing = rules.get(featureId);
        if (existing != null) {
            Set<String> newTargets = new java.util.HashSet<>(existing.targetUsers());
            newTargets.add(userId);
            registerRule(GrayRule.builder()
                .featureId(featureId)
                .enabled(existing.enabled())
                .percentage(existing.percentage())
                .targetUsers(newTargets)
                .excludeUsers(existing.excludeUsers())
                .build());
        }
    }

    private int getUserBucket(String userId, String featureId) {
        String key = userId + ":" + featureId;
        return userBuckets.computeIfAbsent(key, k -> {
            int hash = Math.abs((userId + featureId).hashCode());
            return hash % 100;
        });
    }

    public Map<String, GrayRule> getAllRules() {
        return new ConcurrentHashMap<>(rules);
    }

    public GrayRule getRule(String featureId) {
        return rules.get(featureId);
    }
}
