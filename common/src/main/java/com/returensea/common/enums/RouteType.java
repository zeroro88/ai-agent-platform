package com.returensea.common.enums;

public enum RouteType {
    FAST_TRACK("fast_track", "快轨 - AI Agent 处理"),
    SLOW_TRACK("slow_track", "慢轨 - 传统事务处理"),
    HYBRID("hybrid", "混合 - AI 辅助 + 传统执行"),
    FALLBACK("fallback", "降级 - 兜底策略");

    private final String code;
    private final String description;

    RouteType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
