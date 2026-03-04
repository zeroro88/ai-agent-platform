package com.returensea.common.enums;

public enum IntentType {
    POLICY_CONSULT("policy_consult", "政策咨询"),
    ACTIVITY_QUERY("activity_query", "活动查询"),
    ACTIVITY_CREATE("activity_create", "活动发起"),
    ACTIVITY_REGISTER("activity_register", "活动报名"),
    ORDER_QUERY("order_query", "订单查询"),
    USER_PROFILE("user_profile", "用户信息"),
    CHITCHAT("chitchat", "闲聊"),
    RECOMMEND("recommend", "个性化推荐"),
    OPERATION("operation", "运营操作"),
    UNKNOWN("unknown", "未知");

    private final String code;
    private final String description;

    IntentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static IntentType fromCode(String code) {
        for (IntentType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
