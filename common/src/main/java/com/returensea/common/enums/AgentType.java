package com.returensea.common.enums;

public enum AgentType {
    ACTIVITY("activity", "活动 Agent", "活动推荐、报名引导、状态查询"),
    POLICY("policy", "政策 Agent", "政策解读、匹配推荐、FAQ"),
    OPERATION("operation", "运营 Agent", "内容审核、采集、分析");

    private final String code;
    private final String name;
    private final String description;

    AgentType(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public static AgentType fromCode(String code) {
        for (AgentType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
