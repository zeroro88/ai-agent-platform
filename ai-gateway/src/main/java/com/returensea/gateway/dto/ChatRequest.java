package com.returensea.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String message;
    private String sessionId;
    private String userId;
    private Map<String, Object> context;
    private String traceId;
}
