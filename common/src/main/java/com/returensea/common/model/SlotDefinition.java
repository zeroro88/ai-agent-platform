package com.returensea.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlotDefinition {
    private String name;
    private String description;
    private String type;
    private boolean required;
    private List<String> validValues;
    private String promptTemplate;
}
