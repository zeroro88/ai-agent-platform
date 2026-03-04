package com.returensea.agent.recommend;

import java.util.List;
import java.util.Map;

public interface RecommendationService {
    List<Recommendation> recommend(String userId, String context, int limit);
    
    void recordUserAction(String userId, String action, Map<String, Object> data);
    
    void buildUserPreferenceProfile(String userId);
    
    record Recommendation(
        String id,
        String type,
        String title,
        String description,
        double score,
        Map<String, Object> metadata
    ) {}
}
