package com.returensea.common.enums;

public enum PermissionLevel {
    L0("自动执行", "政策问答、活动介绍、常规推荐", 0),
    L1("提建议+一键确认", "收藏活动、订阅政策、更新非敏感偏好", 1),
    L2("严格二次确认执行", "提交报名、取消预约", 2),
    L3("禁止自动执行", "支付、退款、实名认证、敏感信息变更", 3);

    private final String name;
    private final String description;
    private final int level;

    PermissionLevel(String name, String description, int level) {
        this.name = name;
        this.description = description;
        this.level = level;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getLevel() {
        return level;
    }

    public boolean canAutoExecute() {
        return this == L0 || this == L1;
    }

    public boolean requiresConfirmation() {
        return this == L2;
    }

    public boolean isForbidden() {
        return this == L3;
    }
}
