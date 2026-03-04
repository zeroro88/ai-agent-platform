package com.returensea.agent.memory;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class InMemoryVectorMemoryStore implements VectorMemoryStore {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final Map<String, UserMemory> userMemories = new ConcurrentHashMap<>();

    public InMemoryVectorMemoryStore(EmbeddingModel embeddingModel) {
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void addMemory(String userId, String content, Map<String, Object> metadata) {
        try {
            String memoryId = UUID.randomUUID().toString();
            
            TextSegment segment = TextSegment.from(content);
            segment.metadata().put("memoryId", memoryId);
            segment.metadata().put("userId", userId);
            Map<String, Object> safeMetadata = metadata == null ? Collections.emptyMap() : metadata;
            for (Map.Entry<String, Object> entry : safeMetadata.entrySet()) {
                Object v = entry.getValue();
                segment.metadata().put(entry.getKey(), v == null ? "" : String.valueOf(v));
            }
            
            Embedding embedding = embeddingModel.embed(content).content();
            embeddingStore.add(embedding, segment);
            
            userMemories.put(memoryId, new UserMemory(userId, memoryId, content, safeMetadata));
            
            log.info("Added memory for user {}: {}", userId, memoryId);
            
        } catch (Exception e) {
            log.error("Error adding memory for user {}: {}", userId, e.getMessage(), e);
        }
    }

    @Override
    public List<MemorySearchResult> search(String userId, String query, int topK) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK * 2)
                    .minScore(0.5)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> allMatches = searchResult.matches();

            return allMatches.stream()
                    .filter(match -> {
                        String matchUserId = match.embedded().metadata().getString("userId");
                        return userId.equals(matchUserId);
                    })
                    .limit(topK)
                    .map(match -> {
                        String memoryId = match.embedded().metadata().getString("memoryId");
                        Map<String, Object> metadata = new HashMap<>(match.embedded().metadata().toMap());
                        metadata.remove("memoryId");
                        metadata.remove("userId");
                        
                        return new MemorySearchResult(
                                memoryId,
                                userId,
                                match.embedded().text(),
                                metadata,
                                match.score()
                        );
                    })
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            log.error("Error searching memories for user {}: {}", userId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public void deleteMemory(String userId, String memoryId) {
        userMemories.remove(memoryId);
        log.info("Deleted memory {} for user {}", memoryId, userId);
    }

    @Override
    public void deleteAllUserMemories(String userId) {
        List<String> memoryIdsToRemove = userMemories.entrySet().stream()
                .filter(e -> e.getValue().userId().equals(userId))
                .map(Map.Entry::getKey)
                .toList();
        
        memoryIdsToRemove.forEach(userMemories::remove);
        log.info("Deleted all {} memories for user {}", memoryIdsToRemove.size(), userId);
    }

    private record UserMemory(String userId, String memoryId, String content, Map<String, Object> metadata) {}
}
