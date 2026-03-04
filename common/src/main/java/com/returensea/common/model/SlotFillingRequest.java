package com.returensea.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlotFillingRequest {
    private String sessionId;
    private String userId;
    private String intentType;
    private String userMessage;
    private Map<String, Object> currentSlots;
    private List<SlotDefinition> requiredSlots;
}
