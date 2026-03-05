package com.returensea.gateway.controller;

import com.returensea.common.util.TraceUtil;
import com.returensea.gateway.dto.ChatRequest;
import com.returensea.gateway.dto.ChatResponse;
import com.returensea.gateway.service.AgentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;

@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentGatewayService agentGatewayService;

    @PostMapping
    public Mono<ResponseEntity<ChatResponse>> chat(@RequestBody ChatRequest request) {
        String traceId = TraceUtil.generateTraceId();
        TraceUtil.setTraceId(traceId);
        
        log.info("[{}] Received chat request: userId={}, sessionId={}", 
                traceId, request.getUserId(), request.getSessionId());
        
        request.setTraceId(traceId);
        
        return agentGatewayService.processChat(request)
                .doOnNext(response -> {
                    response.setTraceId(traceId);
                    log.info("[{}] Completed chat request, processTime={}ms", 
                            traceId, response.getProcessTimeMs());
                })
                .doOnError(e -> log.error("[{}] Chat request failed: {}", traceId, e.getMessage(), e))
                .map(ResponseEntity::ok)
                .doFinally(s -> TraceUtil.clear());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody ChatRequest request) {
        String traceId = TraceUtil.generateTraceId();
        TraceUtil.setTraceId(traceId);
        request.setTraceId(traceId);
        log.info("[{}] Received chat stream request: userId={}, sessionId={}", traceId, request.getUserId(), request.getSessionId());
        StreamingResponseBody stream = out -> {
            try {
                agentGatewayService.processChatStream(request)
                        .doOnComplete(() -> log.info("[{}] Chat stream completed", traceId))
                        .doOnError(e -> log.error("[{}] Chat stream failed: {}", traceId, e.getMessage(), e))
                        .doFinally(s -> TraceUtil.clear())
                        .doOnNext(buffer -> writeBuffer(buffer, out))
                        .blockLast();
            } finally {
                TraceUtil.clear();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    private static void writeBuffer(DataBuffer buffer, OutputStream out) {
        try {
            int count = buffer.readableByteCount();
            if (count > 0) {
                byte[] bytes = new byte[count];
                buffer.read(bytes);
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write SSE chunk", e);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
