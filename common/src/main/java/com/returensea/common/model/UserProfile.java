package com.returensea.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {
    private String userId;
    private String openId;
    private String nickname;
    private String city;
    private String education;
    private String major;
    private List<String> interests;
    private List<String> preferredCities;
    private List<String> preferredActivityTypes;
    private Map<String, Integer> behaviorTags;
    private LocalDateTime registeredAt;
    private LocalDateTime lastActiveAt;
    private boolean isAuthenticated;
    private String authenticationLevel;
}
