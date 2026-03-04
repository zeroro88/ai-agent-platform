package com.returensea.agent.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.AgentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final long SSE_TIMEOUT_MS = 120_000;

    private final Orchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-stream-" + System.currentTimeMillis());
        t.setDaemon(true);
        return t;
    });

    @PostMapping("/process")
    public ResponseEntity<AgentResponse> process(@RequestBody AgentRequest request) {
        log.info("Received agent request: sessionId={}, userId={}", request.getSessionId(), request.getUserId());
        AgentResponse response = orchestrator.process(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/process-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processStream(@RequestBody AgentRequest request) {
        log.info("Received stream request: sessionId={}, userId={}", request.getSessionId(), request.getUserId());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> log.debug("SSE completed for sessionId={}", request.getSessionId()));
        emitter.onTimeout(() -> log.warn("SSE timeout for sessionId={}", request.getSessionId()));
        streamExecutor.execute(() -> {
            try {
                orchestrator.processStream(
                        request,
                        chunk -> sendEvent(emitter, "contentDelta", chunk),
                        response -> {
                            try {
                                sendEvent(emitter, "done", response);
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("Error sending done event", e);
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("Error in processStream", e);
                try {
                    sendEvent(emitter, "error", Map.of("error", e.getMessage()));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String type, Object data) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", type, "payload", data));
            emitter.send(SseEmitter.event().data(json));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize SSE payload", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to send SSE", e);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("already completed")) {
                log.trace("SSE already completed, skipping send");
                return;
            }
            throw e;
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
