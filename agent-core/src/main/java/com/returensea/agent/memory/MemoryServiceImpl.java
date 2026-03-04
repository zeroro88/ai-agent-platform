package com.returensea.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.common.constants.AgentConstants;
import com.returensea.common.model.Memory;
import com.returensea.common.model.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    @Autowired(required = false)
    private VectorMemoryStore vectorMemoryStore;
    private final ObjectMapper objectMapper;
    private final Map<String, CacheEntry> localStore = new ConcurrentHashMap<>();

    private static final String WORKING_KEY = "agent:working:";
    private static final String SESSION_KEY = "agent:session:";
    private static final String PROFILE_KEY = "agent:profile:";
    private static final String LONGTERM_KEY = "agent:longterm:";
    private static final String SLOT_STATE_KEY = "agent:slots:";

    @Override
    public void updateWorkingMemory(String sessionId, String userId, String message) {
        try {
            String key = WORKING_KEY + sessionId + ":" + userId;
            Map<String, Object> memory = getWorkingMemory(sessionId, userId).orElse(new HashMap<>());
            
            List<String> history = (List<String>) memory.computeIfAbsent("history", k -> new ArrayList<>());
            ((List<String>) history).add(message);
            
            if (((List<String>) history).size() > 10) {
                ((List<String>) history).remove(0);
            }
            
            memory.put("lastUpdate", System.currentTimeMillis());
            
            setValue(key, objectMapper.writeValueAsString(memory), Duration.ofMinutes(5));
        } catch (JsonProcessingException e) {
            log.error("Error updating working memory", e);
        }
    }

    @Override
    public Optional<Map<String, Object>> getWorkingMemory(String sessionId, String userId) {
        try {
            String key = WORKING_KEY + sessionId + ":" + userId;
            String value = getValue(key);
            if (value != null) {
                return Optional.of(objectMapper.readValue(value, new TypeReference<>() {}));
            }
        } catch (JsonProcessingException e) {
            log.error("Error getting working memory", e);
        }
        return Optional.empty();
    }

    @Override
    public void setWorkingMemoryKey(String sessionId, String userId, String key, Object value) {
        try {
            String fullKey = WORKING_KEY + sessionId + ":" + userId;
            Map<String, Object> memory = getWorkingMemory(sessionId, userId).orElse(new HashMap<>());
            memory.put(key, value);
            memory.put("lastUpdate", System.currentTimeMillis());
            setValue(fullKey, objectMapper.writeValueAsString(memory), Duration.ofMinutes(5));
        } catch (JsonProcessingException e) {
            log.error("Error setting working memory key", e);
        }
    }

    @Override
    public void saveToSessionMemory(String sessionId, String userId, String userMessage, String assistantMessage) {
        try {
            String key = SESSION_KEY + sessionId + ":" + userId;
            List<Map<String, String>> history = getSessionHistory(sessionId, userId);
            
            Map<String, String> exchange = new HashMap<>();
            exchange.put("user", userMessage);
            exchange.put("assistant", assistantMessage);
            exchange.put("timestamp", String.valueOf(System.currentTimeMillis()));
            
            history.add(exchange);
            
            setValue(key, objectMapper.writeValueAsString(history), Duration.ofMinutes(AgentConstants.SESSION_MEMORY_TTL_MINUTES));
        } catch (JsonProcessingException e) {
            log.error("Error saving session memory", e);
        }
    }

    private List<Map<String, String>> getSessionHistory(String sessionId, String userId) {
        try {
            String key = SESSION_KEY + sessionId + ":" + userId;
            String value = getValue(key);
            if (value != null) {
                return objectMapper.readValue(value, new TypeReference<>() {});
            }
        } catch (JsonProcessingException e) {
            log.error("Error getting session history", e);
        }
        return new ArrayList<>();
    }

    @Override
    public Optional<List<Map<String, Object>>> getSessionMemory(String sessionId, String userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, String> item : getSessionHistory(sessionId, userId)) {
            Map<String, Object> converted = new HashMap<>();
            converted.putAll(item);
            result.add(converted);
        }
        return Optional.of(result);
    }

    @Override
    public void saveToLongTermMemory(String userId, String type, Map<String, Object> content) {
        try {
            String key = LONGTERM_KEY + userId;
            List<Map<String, Object>> memories = getLongTermMemoryList(userId);
            Map<String, Object> memory = new HashMap<>();
            memory.put("type", type);
            memory.put("content", content);
            memory.put("timestamp", System.currentTimeMillis());
            memories.add(memory);
            setValue(key, objectMapper.writeValueAsString(memories), null);

            if (vectorMemoryStore != null) {
                String contentText = objectMapper.writeValueAsString(content);
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", type);
                meta.put("timestamp", System.currentTimeMillis());
                vectorMemoryStore.addMemory(userId, contentText, meta);
            }
            log.info("Saved long-term memory for user: {}, type: {}, total memories: {}", userId, type, memories.size());
        } catch (JsonProcessingException e) {
            log.error("Error saving long-term memory", e);
        }
    }

    private List<Map<String, Object>> getLongTermMemoryList(String userId) {
        try {
            String key = LONGTERM_KEY + userId;
            String value = getValue(key);
            if (value != null) {
                return objectMapper.readValue(value, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (JsonProcessingException e) {
            log.error("Error getting long-term memory list", e);
        }
        return new ArrayList<>();
    }

    @Override
    public Optional<List<Memory>> getLongTermMemory(String userId) {
        try {
            String key = LONGTERM_KEY + userId;
            String value = getValue(key);
            if (value != null) {
                List<Map<String, Object>> rawList = objectMapper.readValue(value, new TypeReference<List<Map<String, Object>>>() {});
                List<Memory> memories = new ArrayList<>();
                for (Map<String, Object> m : rawList) {
                    memories.add(Memory.builder()
                            .memoryId(UUID.randomUUID().toString())
                            .userId(userId)
                            .type(Memory.MemoryType.LONG_TERM)
                            .content(m)
                            .createdAt(java.time.LocalDateTime.now())
                            .ttlSeconds(0)
                            .build());
                }
                return Optional.of(memories);
            }
        } catch (JsonProcessingException e) {
            log.error("Error getting long-term memory", e);
        }
        return Optional.empty();
    }

    @Override
    public List<VectorMemoryStore.MemorySearchResult> searchLongTermMemory(String userId, String query, int topK) {
        if (vectorMemoryStore == null) {
            return List.of();
        }
        return vectorMemoryStore.search(userId, query, topK);
    }

    @Override
    public Optional<UserProfile> getUserProfile(String userId) {
        try {
            String key = PROFILE_KEY + userId;
            String value = getValue(key);
            if (value != null) {
                return Optional.of(objectMapper.readValue(value, UserProfile.class));
            }
        } catch (JsonProcessingException e) {
            log.error("Error getting user profile", e);
        }
        return Optional.empty();
    }

    @Override
    public void updateUserProfile(String userId, UserProfile profile) {
        try {
            String key = PROFILE_KEY + userId;
            setValue(key, objectMapper.writeValueAsString(profile), null);
            
            Map<String, Object> memoryContent = new HashMap<>();
            memoryContent.put("action", "profile_update");
            memoryContent.put("profile", profile);
            saveToLongTermMemory(userId, "profile_update", memoryContent);
            
            log.info("Updated user profile for: {}", userId);
        } catch (JsonProcessingException e) {
            log.error("Error updating user profile", e);
        }
    }

    private void setValue(String key, String value, Duration ttl) {
        if (redisTemplate != null) {
            if (ttl != null) {
                redisTemplate.opsForValue().set(key, value, ttl);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
            return;
        }
        long expireAt = ttl == null ? 0L : System.currentTimeMillis() + ttl.toMillis();
        localStore.put(key, new CacheEntry(value, expireAt));
    }

    private String getValue(String key) {
        if (redisTemplate != null) {
            return redisTemplate.opsForValue().get(key);
        }
        CacheEntry entry = localStore.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt > 0 && System.currentTimeMillis() > entry.expireAt) {
            localStore.remove(key);
            return null;
        }
        return entry.value;
    }

    @Override
    public void putSlotState(String sessionId, String userId, String intentType, Map<String, Object> slots) {
        try {
            String key = SLOT_STATE_KEY + sessionId + ":" + userId + ":" + intentType;
            setValue(key, objectMapper.writeValueAsString(slots != null ? slots : new HashMap<>()), Duration.ofMinutes(10));
        } catch (JsonProcessingException e) {
            log.error("Error putting slot state", e);
        }
    }

    @Override
    public Optional<Map<String, Object>> getSlotState(String sessionId, String userId, String intentType) {
        try {
            String key = SLOT_STATE_KEY + sessionId + ":" + userId + ":" + intentType;
            String value = getValue(key);
            if (value != null) {
                return Optional.of(objectMapper.readValue(value, new TypeReference<>() {}));
            }
        } catch (JsonProcessingException e) {
            log.error("Error getting slot state", e);
        }
        return Optional.empty();
    }

    @Override
    public void clearSlotState(String sessionId, String userId, String intentType) {
        putSlotState(sessionId, userId, intentType, new HashMap<>());
    }

    private record CacheEntry(String value, long expireAt) {}
}
