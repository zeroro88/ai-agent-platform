package com.returensea.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图路由规则：从 YAML 加载关键词、意图→Agent 映射等，代码只做「按规则匹配」。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "intent-routing")
public class IntentRoutingProperties {

    /** 权限等级关键词：L0/L1/L2/L3 -> 关键词列表 */
    private Map<String, List<String>> permission = defaultPermission();

    /** 意图分类关键词：policy/activity-create/activity/order/user-profile/recommend */
    private Map<String, List<String>> intentKeywords = defaultIntentKeywords();

    /** 意图 → Agent 映射：IntentType.name() -> AgentType.name() */
    private Map<String, String> intentAgentMapping = defaultIntentAgentMapping();

    /** 实体抽取：cities 等 */
    private EntityConfig entities = new EntityConfig();

    /** 置信度加成（关键词与意图一致时）：policy/activity-query/activity-create */
    private Map<String, Double> confidenceBoost = defaultConfidenceBoost();

    @Data
    public static class EntityConfig {
        private List<String> cities = defaultCities();
    }

    private static Map<String, List<String>> defaultPermission() {
        Map<String, List<String>> m = new HashMap<>();
        m.put("L0", List.of("政策", "补贴", "落户", "创业", "优惠", "介绍", "是什么", "怎么办"));
        m.put("L1", List.of("收藏", "订阅", "关注", "偏好", "推荐"));
        m.put("L2", List.of("报名", "参加", "预约", "取消", "退款"));
        m.put("L3", List.of("支付", "转账", "认证", "实名", "绑定银行卡"));
        return m;
    }

    private static Map<String, List<String>> defaultIntentKeywords() {
        Map<String, List<String>> m = new HashMap<>();
        m.put("policy", List.of("政策", "补贴", "落户", "购房", "税费", "优惠", "扶持"));
        m.put("activity-create", List.of("创建活动", "发起活动", "发布活动", "组织活动", "办活动", "搞活动"));
        m.put("activity", List.of("活动", "报名", "参加", "会议", "讲座", "沙龙", "聚会", "比赛"));
        m.put("order", List.of("订单", "支付", "退款", "票券", "核销"));
        m.put("user-profile", List.of("我", "我的", "资料", "信息"));
        m.put("recommend", List.of("推荐", "帮我找", "想要"));
        return m;
    }

    private static Map<String, String> defaultIntentAgentMapping() {
        Map<String, String> m = new HashMap<>();
        m.put("POLICY_CONSULT", "POLICY");
        m.put("ACTIVITY_QUERY", "ACTIVITY");
        m.put("ACTIVITY_CREATE", "ACTIVITY");
        m.put("ACTIVITY_REGISTER", "ACTIVITY");
        m.put("ORDER_QUERY", "ACTIVITY");
        m.put("RECOMMEND", "ACTIVITY");
        m.put("USER_PROFILE", "ACTIVITY");
        m.put("OPERATION", "OPERATION");
        m.put("CHITCHAT", "ACTIVITY");
        m.put("UNKNOWN", "ACTIVITY");
        return m;
    }

    private static List<String> defaultCities() {
        return List.of("北京", "上海", "深圳", "广州", "杭州", "成都", "武汉", "西安");
    }

    private static Map<String, Double> defaultConfidenceBoost() {
        Map<String, Double> m = new HashMap<>();
        m.put("policy", 0.15);
        m.put("activity-query", 0.15);
        m.put("activity-create", 0.2);
        return m;
    }

    /** 空列表时使用默认值，避免 NPE */
    public List<String> getPermissionKeywords(String level) {
        List<String> list = permission != null ? permission.get(level) : null;
        return list != null ? list : List.of();
    }

    public List<String> getIntentKeywords(String key) {
        List<String> list = intentKeywords != null ? intentKeywords.get(key) : null;
        return list != null ? list : List.of();
    }

    public String getAgentForIntent(String intentTypeName) {
        return intentAgentMapping != null ? intentAgentMapping.getOrDefault(intentTypeName, "ACTIVITY") : "ACTIVITY";
    }

    public List<String> getEntityCities() {
        return entities != null && entities.getCities() != null ? entities.getCities() : defaultCities();
    }

    public double getConfidenceBoost(String key) {
        return confidenceBoost != null && confidenceBoost.containsKey(key) ? confidenceBoost.get(key) : 0.0;
    }
}
