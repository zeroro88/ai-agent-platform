package com.returensea.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 活动搜索/推荐结果中的单条结构化数据，供前端卡片展示与稳定引用活动 ID。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedActivity {
    private String id;
    private String title;
    private String city;
    private String location;
    /** 已格式化的展示用时间字符串 */
    private String eventTime;
    private String recommendReason;
    private Integer capacity;
    private Integer registered;
    /** 推荐列表中的顺序，从 1 起 */
    private Integer ordinal;
}
