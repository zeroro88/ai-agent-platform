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
public class SlotFillingResult {
    private Map<String, Object> filledSlots;
    private List<String> missingSlots;
    private boolean isComplete;
    private String clarificationMessage;
}
