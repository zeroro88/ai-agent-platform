package com.returensea.agent.memory;

import com.returensea.common.model.Memory;
import com.returensea.common.model.UserProfile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MemoryService {
    void updateWorkingMemory(String sessionId, String userId, String message);
    Optional<Map<String, Object>> getWorkingMemory(String sessionId, String userId);
    /** 写入工作记忆中的单个 key，用于存储如 lastActivityIds 等 */
    void setWorkingMemoryKey(String sessionId, String userId, String key, Object value);
    /** 删除工作记忆中的单个 key */
    void removeWorkingMemoryKey(String sessionId, String userId, String key);
    void saveToSessionMemory(String sessionId, String userId, String userMessage, String assistantMessage);
    Optional<List<Map<String, Object>>> getSessionMemory(String sessionId, String userId);
    void saveToLongTermMemory(String userId, String type, Map<String, Object> content);
    Optional<List<Memory>> getLongTermMemory(String userId);
    /** 长期记忆语义检索：按 query 向量检索该用户长期记忆，供个性化与推荐使用 */
    List<VectorMemoryStore.MemorySearchResult> searchLongTermMemory(String userId, String query, int topK);
    Optional<UserProfile> getUserProfile(String userId);
    void updateUserProfile(String userId, UserProfile profile);

    /** 槽位填充状态：按会话+意图存储未收齐时的已填槽位，供多轮补全 */
    void putSlotState(String sessionId, String userId, String intentType, Map<String, Object> slots);
    Optional<Map<String, Object>> getSlotState(String sessionId, String userId, String intentType);
    void clearSlotState(String sessionId, String userId, String intentType);
}
