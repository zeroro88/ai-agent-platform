package com.returensea.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.common.enums.RouteType;
import com.returensea.gateway.client.AgentCoreClient;
import com.returensea.gateway.dto.ChatRequest;
import com.returensea.gateway.dto.ChatResponse;
import com.returensea.gateway.dto.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentGatewayService {

    private static final DataBufferFactory BUFFER_FACTORY = new DefaultDataBufferFactory();

    private final IntentRouterService intentRouterService;
    private final AgentCoreClient agentCoreClient;
    private final ObjectMapper objectMapper;

    public Mono<ChatResponse> processChat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        
        RouteResult routeResult = intentRouterService.route(request);
        log.info("[{}] Routed to: agent={}, routeType={}, permission={}", 
                request.getTraceId(), routeResult.getTargetAgent(), routeResult.getRouteType(), routeResult.getRequiredPermission());
        
        if (routeResult.isNeedsClarification()) {
            return Mono.just(buildClarificationResponse(request, routeResult, startTime));
        }

        if (routeResult.getRouteType() == RouteType.SLOW_TRACK) {
            return Mono.just(buildSlowTrackResponse(request, routeResult, startTime));
        }

        return handleFastTrack(request, routeResult, startTime);
    }

    /**
     * 流式处理：仅 fast track 时透传 agent-core 的 SSE 流；否则返回单条 SSE 事件（澄清或慢通道文案）。
     */
    public Flux<DataBuffer> processChatStream(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        RouteResult routeResult = intentRouterService.route(request);
        log.info("[{}] Stream routed to: agent={}, routeType={}", request.getTraceId(), routeResult.getTargetAgent(), routeResult.getRouteType());

        if (routeResult.isNeedsClarification()) {
            ChatResponse resp = buildClarificationResponse(request, routeResult, startTime);
            return singleEventFlux("done", resp);
        }
        if (routeResult.getRouteType() == RouteType.SLOW_TRACK) {
            ChatResponse resp = buildSlowTrackResponse(request, routeResult, startTime);
            return singleEventFlux("done", resp);
        }
        return agentCoreClient.processStream(request, routeResult);
    }

    private Flux<DataBuffer> singleEventFlux(String type, ChatResponse payload) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("payload", payload);
        try {
            String json = objectMapper.writeValueAsString(event);
            String sseLine = "data: " + json + "\n\n";
            return Flux.just(BUFFER_FACTORY.wrap(sseLine.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize SSE event", e);
            return Flux.empty();
        }
    }

    private Mono<ChatResponse> handleFastTrack(ChatRequest request, RouteResult routeResult, long startTime) {
        log.info("[{}] Routing to fast track - Agent: {}, Intent: {}", 
                request.getTraceId(), routeResult.getTargetAgent(), routeResult.getIntentType());

        return agentCoreClient.processAgentRequest(request, routeResult)
                .map(response -> ChatResponse.builder()
                        .responseId(response.getResponseId() != null ? response.getResponseId() : UUID.randomUUID().toString())
                        .traceId(request.getTraceId())
                        .content(response.getContent())
                        .agentType(response.getAgentType())
                        .processTimeMs(System.currentTimeMillis() - startTime)
                        .timestamp(LocalDateTime.now())
                        .error(response.getError())
                        .processingSteps(response.getProcessingSteps())
                        .build())
                .onErrorReturn(buildErrorResponse(request, "Agent processing failed", System.currentTimeMillis() - startTime));
    }

    private ChatResponse buildClarificationResponse(ChatRequest request, RouteResult routeResult, long startTime) {
        return ChatResponse.builder()
                .responseId(UUID.randomUUID().toString())
                .traceId(request.getTraceId())
                .content(routeResult.getClarification())
                .agentType(routeResult.getTargetAgent().name())
                .processTimeMs(System.currentTimeMillis() - startTime)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private ChatResponse buildSlowTrackResponse(ChatRequest request, RouteResult routeResult, long startTime) {
        return ChatResponse.builder()
                .responseId(UUID.randomUUID().toString())
                .traceId(request.getTraceId())
                .content("此操作需要通过传统方式处理，请稍后...")
                .agentType("SYSTEM")
                .processTimeMs(System.currentTimeMillis() - startTime)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private ChatResponse buildErrorResponse(ChatRequest request, String error, long processTime) {
        return ChatResponse.builder()
                .responseId(UUID.randomUUID().toString())
                .traceId(request.getTraceId())
                .content("抱歉，处理您的请求时出现错误：" + error)
                .error(error)
                .processTimeMs(processTime)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
