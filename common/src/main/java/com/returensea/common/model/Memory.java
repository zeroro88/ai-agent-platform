package com.returensea.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Memory {
    private String memoryId;
    private String userId;
    private MemoryType type;
    private Map<String, Object> content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long ttlSeconds;

    public enum MemoryType {
        WORKING,
        SESSION,
        LONG_TERM
    }
}
