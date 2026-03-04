package com.returensea.agent.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface VectorMemoryStore {
    void addMemory(String userId, String content, Map<String, Object> metadata);
    
    List<MemorySearchResult> search(String userId, String query, int topK);
    
    void deleteMemory(String userId, String memoryId);
    
    void deleteAllUserMemories(String userId);
    
    record MemorySearchResult(
        String memoryId,
        String userId,
        String content,
        Map<String, Object> metadata,
        double score
    ) {}
}
