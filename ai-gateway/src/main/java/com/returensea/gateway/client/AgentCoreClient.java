package com.returensea.gateway.client;

import com.returensea.common.model.RecommendedActivity;
import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.gateway.dto.ChatRequest;
import com.returensea.gateway.dto.ChatResponse;
import com.returensea.gateway.dto.RouteResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AgentCoreClient {

    private final WebClient webClient;

    public AgentCoreClient(@Qualifier("agentCoreWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<ChatResponse> processAgentRequest(ChatRequest chatRequest, RouteResult routeResult) {
        Map<String, Object> context = new HashMap<>();
        if (chatRequest.getContext() != null) {
            context.putAll(chatRequest.getContext());
        }
        if (routeResult.getExtractedEntities() != null) {
            context.putAll(routeResult.getExtractedEntities());
        }
        context.put("intentType", routeResult.getIntentType().name());

        AgentRequestDTO request = AgentRequestDTO.builder()
                .sessionId(chatRequest.getSessionId())
                .userId(chatRequest.getUserId())
                .message(chatRequest.getMessage())
                .agentType(routeResult.getTargetAgent() != null ? routeResult.getTargetAgent().name() : null)
                .requiredPermission(routeResult.getRequiredPermission())
                .context(context)
                .traceId(chatRequest.getTraceId())
                .build();

        log.info("[{}] Calling agent-core with agentType={}", chatRequest.getTraceId(), routeResult.getTargetAgent());

        return webClient.post()
                .uri("/api/v1/agent/process")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AgentResponseDTO.class)
                .timeout(java.time.Duration.ofSeconds(30))
                .onErrorResume(e -> {
                    log.error("[{}] Error calling agent-core: {}", chatRequest.getTraceId(), e.getMessage());
                    return Mono.just(AgentResponseDTO.builder()
                            .content("服务暂时不可用，请稍后重试")
                            .error(e.getMessage())
                            .timestamp(LocalDateTime.now())
                            .build());
                })
                .map(this::toChatResponse);
    }

    /**
     * 流式请求 agent-core process-stream，原样透传 SSE 流。
     */
    public Flux<DataBuffer> processStream(ChatRequest chatRequest, RouteResult routeResult) {
        Map<String, Object> context = new HashMap<>();
        if (chatRequest.getContext() != null) {
            context.putAll(chatRequest.getContext());
        }
        if (routeResult.getExtractedEntities() != null) {
            context.putAll(routeResult.getExtractedEntities());
        }
        context.put("intentType", routeResult.getIntentType().name());

        AgentRequestDTO request = AgentRequestDTO.builder()
                .sessionId(chatRequest.getSessionId())
                .userId(chatRequest.getUserId())
                .message(chatRequest.getMessage())
                .agentType(routeResult.getTargetAgent() != null ? routeResult.getTargetAgent().name() : null)
                .requiredPermission(routeResult.getRequiredPermission())
                .context(context)
                .traceId(chatRequest.getTraceId())
                .build();

        log.info("[{}] Calling agent-core POST /api/v1/agent/process-stream, agentType={}, messageLen={}",
                chatRequest.getTraceId(), routeResult.getTargetAgent(),
                chatRequest.getMessage() != null ? chatRequest.getMessage().length() : 0);

        return webClient.post()
                .uri("/api/v1/agent/process-stream")
                .bodyValue(request)
                .exchangeToFlux(r -> r.bodyToFlux(DataBuffer.class));
    }

    private ChatResponse toChatResponse(AgentResponseDTO dto) {
        return ChatResponse.builder()
                .responseId(dto.getResponseId())
                .traceId(dto.getTraceId())
                .content(dto.getContent())
                .agentType(dto.getAgentType())
                .processTimeMs(dto.getProcessTimeMs())
                .timestamp(dto.getTimestamp())
                .error(dto.getError())
                .errorDetail(dto.getErrorDetail())
                .processingSteps(dto.getProcessingSteps())
                .recommendedActivities(dto.getRecommendedActivities())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentRequestDTO {
        private String sessionId;
        private String userId;
        private String message;
        private String agentType;
        private PermissionLevel requiredPermission;
        private Map<String, Object> context;
        private String traceId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentResponseDTO {
        private String responseId;
        private String sessionId;
        private String userId;
        private String content;
        private String agentType;
        private List<ActionDTO> actions;
        private String confidence;
        private boolean needsConfirmation;
        private String confirmationPrompt;
        private LocalDateTime timestamp;
        private long processTimeMs;
        private String error;
        private String traceId;
        private String errorDetail;
        private List<String> processingSteps;
        private List<RecommendedActivity> recommendedActivities;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ActionDTO {
            private String type;
            private String toolName;
            private Map<String, Object> parameters;
        }
    }
}
