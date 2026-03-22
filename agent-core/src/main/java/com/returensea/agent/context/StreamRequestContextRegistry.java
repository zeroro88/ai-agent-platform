package com.returensea.agent.context;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式请求下，LangChain4j 可能在无 {@link AgentContextHolder} / MDC 的线程上提交工具任务（如 ForkJoinPool）。
 * 在 {@link com.returensea.agent.orchestrator.OrchestratorImpl#processStream} 入口按 traceId 登记 session/user，
 * 供 {@link AgentContextPropagatingExecutor} 在快照为空时回退解析。
 */
@Component
public class StreamRequestContextRegistry {

    public record SessionUser(String sessionId, String userId) {}

    private final ConcurrentHashMap<String, SessionUser> byTraceId = new ConcurrentHashMap<>();

    public void bind(String traceId, String sessionId, String userId) {
        if (traceId != null && !traceId.isEmpty() && sessionId != null && userId != null) {
            byTraceId.put(traceId, new SessionUser(sessionId, userId));
        }
    }

    public void unbind(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            byTraceId.remove(traceId);
        }
    }

    public SessionUser resolve(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return null;
        }
        return byTraceId.get(traceId);
    }

    /**
     * 当 LangChain4j 在无任何 MDC 的线程上提交工具时，无法解析 traceId。
     * 若当前 JVM 内仅有一条流式请求已 bind（常见本地单测），则退回该会话，避免 register 读不到 lastActivityIds。
     */
    public SessionUser soleActiveSession() {
        if (byTraceId.size() != 1) {
            return null;
        }
        return byTraceId.values().iterator().next();
    }
}
