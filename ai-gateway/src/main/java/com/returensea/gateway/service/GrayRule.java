package com.returensea.gateway.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrayRule {
    private String featureId;
    private boolean enabled;
    private int percentage;
    private Set<String> targetUsers;
    private Set<String> excludeUsers;

    public boolean enabled() {
        return enabled;
    }

    public int percentage() {
        return percentage;
    }

    public Set<String> targetUsers() {
        return targetUsers;
    }

    public Set<String> excludeUsers() {
        return excludeUsers;
    }

    public String featureId() {
        return featureId;
    }
}
