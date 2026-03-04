package com.returensea.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 agent-core 使用真实 Redis（docker-compose 中的 agent-redis）。
 * 运行前请先启动: docker compose -f docker/docker-compose.yml up -d redis
 */
@SpringBootTest(classes = com.returensea.agent.AgentCoreApplication.class)
@ActiveProfiles("middleware")
class RedisMemoryIntegrationTest {

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("Redis 可用时 slot 状态写入并读回")
    void slotState_persistedInRedis() {
        String sessionId = "test-session-" + System.currentTimeMillis();
        String userId = "user-1";
        String intentType = "policy_query";
        Map<String, Object> slots = new HashMap<>();
        slots.put("city", "上海");
        slots.put("keyword", "落户");

        memoryService.putSlotState(sessionId, userId, intentType, slots);
        Optional<Map<String, Object>> read = memoryService.getSlotState(sessionId, userId, intentType);

        assertThat(read).isPresent();
        assertThat(read.get().get("city")).isEqualTo("上海");
        assertThat(read.get().get("keyword")).isEqualTo("落户");

        String key = "agent:slots:" + sessionId + ":" + userId + ":" + intentType;
        Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isPositive();
        assertThat(ttlSeconds).isLessThanOrEqualTo(600L);
    }

    @Test
    @DisplayName("StringRedisTemplate 可用，说明已连接真实 Redis")
    void redisTemplate_connected() {
        String key = "agent:test:ping:" + System.currentTimeMillis();
        redisTemplate.opsForValue().set(key, "pong");
        String value = redisTemplate.opsForValue().get(key);
        redisTemplate.delete(key);
        assertThat(value).isEqualTo("pong");
    }
}
