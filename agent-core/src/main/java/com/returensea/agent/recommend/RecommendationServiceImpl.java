package com.returensea.agent.recommend;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecommendationServiceImpl implements RecommendationService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final Map<String, UserPreferenceProfile> userProfiles = new ConcurrentHashMap<>();
    private final Map<String, List<UserAction>> userActions = new ConcurrentHashMap<>();

    private final List<RecommendableItem> catalog;

    public RecommendationServiceImpl(EmbeddingModel embeddingModel) {
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        this.embeddingModel = embeddingModel;
        this.catalog = initializeCatalog();
        indexCatalog();
    }

    private List<RecommendableItem> initializeCatalog() {
        return List.of(
            new RecommendableItem("act-001", "ACTIVITY", "海归人才招聘会", "50+知名企业现场招聘", Map.of("city", "北京", "category", "就业")),
            new RecommendableItem("act-002", "ACTIVITY", "创业分享沙龙", "海归创业者分享经验", Map.of("city", "北京", "category", "创业")),
            new RecommendableItem("act-003", "ACTIVITY", "职业发展论坛", "行业大咖分享职业路径", Map.of("city", "上海", "category", "职业发展")),
            new RecommendableItem("act-004", "ACTIVITY", "创业大赛", "争夺创业扶持资金", Map.of("city", "深圳", "category", "创业")),
            new RecommendableItem("pol-001", "POLICY", "北京落户政策", "海归落户北京指南", Map.of("city", "北京", "category", "落户")),
            new RecommendableItem("pol-002", "POLICY", "上海创业补贴", "上海创业扶持政策", Map.of("city", "上海", "category", "创业")),
            new RecommendableItem("pol-003", "POLICY", "深圳购房优惠", "海归购房补贴政策", Map.of("city", "深圳", "category", "购房")),
            new RecommendableItem("pol-004", "POLICY", "生活补贴标准", "各地生活补贴汇总", Map.of("city", "全国", "category", "补贴")),
            new RecommendableItem("rec-001", "CONTENT", "海归落户全攻略", "完整落户流程指南", Map.of("type", "guide")),
            new RecommendableItem("rec-002", "CONTENT", "创业避坑指南", "创业者经验总结", Map.of("type", "guide"))
        );
    }

    private void indexCatalog() {
        for (RecommendableItem item : catalog) {
            try {
                String text = item.title() + " " + item.description();
                TextSegment segment = TextSegment.from(text);
                segment.metadata().put("id", item.id());
                segment.metadata().put("type", item.type());
                
                Embedding embedding = embeddingModel.embed(text).content();
                embeddingStore.add(embedding, segment);
                
                log.info("Indexed catalog item: {}", item.id());
            } catch (Exception e) {
                log.error("Error indexing catalog item {}: {}", item.id(), e.getMessage());
            }
        }
    }

    @Override
    public List<Recommendation> recommend(String userId, String context, int limit) {
        UserPreferenceProfile profile = userProfiles.get(userId);
        
        try {
            Embedding queryEmbedding = embeddingModel.embed(context).content();
            
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(limit * 2)
                    .minScore(0.5)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

            List<Recommendation> recommendations = matches.stream()
                .map(match -> {
                    String itemId = match.embedded().metadata().getString("id");
                    RecommendableItem item = catalog.stream()
                        .filter(i -> i.id().equals(itemId))
                        .findFirst()
                        .orElse(null);
                    
                    if (item == null) {
                        return null;
                    }
                    
                    double score = match.score();
                    
                    if (profile != null) {
                        score = applyUserPreferenceBoost(profile, item, score);
                    }
                    
                    return new Recommendation(
                        item.id(),
                        item.type(),
                        item.title(),
                        item.description(),
                        score,
                        item.metadata()
                    );
                })
                .filter(Objects::nonNull)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .collect(Collectors.toList());
            
            return recommendations;
            
        } catch (Exception e) {
            log.error("Error generating recommendations for user {}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private double applyUserPreferenceBoost(UserPreferenceProfile profile, RecommendableItem item, double baseScore) {
        double boost = 1.0;
        
        String itemCity = (String) item.metadata().get("city");
        if (itemCity != null && profile.preferredCities().contains(itemCity)) {
            boost += 0.2;
        }
        
        String itemCategory = (String) item.metadata().get("category");
        if (itemCategory != null && profile.preferredCategories().contains(itemCategory)) {
            boost += 0.3;
        }
        
        if ("ACTIVITY".equals(item.type()) && profile.activityViews() > 0) {
            boost += 0.1;
        }
        
        if ("POLICY".equals(item.type()) && profile.policyViews() > 0) {
            boost += 0.1;
        }
        
        return baseScore * boost;
    }

    @Override
    public void recordUserAction(String userId, String action, Map<String, Object> data) {
        UserAction userAction = new UserAction(action, data, System.currentTimeMillis());
        
        userActions.computeIfAbsent(userId, k -> new ArrayList<>()).add(userAction);
        
        log.info("Recorded action {} for user {}", action, userId);
        
        if (userActions.get(userId).size() >= 5) {
            buildUserPreferenceProfile(userId);
        }
    }

    @Override
    public void buildUserPreferenceProfile(String userId) {
        List<UserAction> actions = userActions.getOrDefault(userId, Collections.emptyList());
        
        if (actions.isEmpty()) {
            return;
        }
        
        Set<String> cities = new HashSet<>();
        Set<String> categories = new HashSet<>();
        int activityViews = 0;
        int policyViews = 0;
        
        for (UserAction action : actions) {
            if ("view".equals(action.action()) || "click".equals(action.action())) {
                Object itemType = action.data().get("type");
                Object city = action.data().get("city");
                Object category = action.data().get("category");
                
                if (city instanceof String) {
                    cities.add((String) city);
                }
                if (category instanceof String) {
                    categories.add((String) category);
                }
                if ("ACTIVITY".equals(itemType)) {
                    activityViews++;
                }
                if ("POLICY".equals(itemType)) {
                    policyViews++;
                }
            }
        }
        
        UserPreferenceProfile profile = new UserPreferenceProfile(
            userId, cities, categories, activityViews, policyViews, System.currentTimeMillis()
        );
        
        userProfiles.put(userId, profile);
        
        log.info("Built preference profile for user {}: cities={}, categories={}", 
            userId, cities, categories);
    }

    private record RecommendableItem(String id, String type, String title, String description, Map<String, Object> metadata) {}
    private record UserAction(String action, Map<String, Object> data, long timestamp) {}
    private record UserPreferenceProfile(String userId, Set<String> preferredCities, Set<String> preferredCategories, 
                                        int activityViews, int policyViews, long lastUpdated) {}
}
