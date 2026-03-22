package com.returensea.agent.context;

import com.returensea.common.util.TraceUtil;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * LangChain4j 在使用流式模型时会在独立线程执行工具，
 * 而 {@link AgentContextHolder} 为 ThreadLocal，仅挂在部分调用链线程上。
 * <p>
 * 多轮工具时，第二次工具的 {@link #execute(Runnable)} 可能由 ForkJoinPool 等线程触发，
 * 该线程上既无 Holder 也无 MDC，仅靠快照会丢失 session。此时通过 {@link StreamRequestContextRegistry}
 * 用 traceId 回退解析本次流式请求的 sessionId/userId。
 */
public final class AgentContextPropagatingExecutor implements Executor {

    private final Executor delegate;
    private final StreamRequestContextRegistry streamRequestContextRegistry;

    public AgentContextPropagatingExecutor(Executor delegate, StreamRequestContextRegistry streamRequestContextRegistry) {
        this.delegate = delegate;
        this.streamRequestContextRegistry = streamRequestContextRegistry;
    }

    @Override
    public void execute(Runnable command) {
        String sessionId = AgentContextHolder.getSessionId();
        String userId = AgentContextHolder.getUserId();
        String currentTurnUserMessage = AgentContextHolder.getCurrentTurnUserMessage();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        String traceScratch = TraceUtil.getTraceId();
        if (traceScratch == null && mdcSnapshot != null) {
            traceScratch = mdcSnapshot.get(TraceUtil.TRACE_ID);
        }
        final String resolvedTraceId = traceScratch;

        if (sessionId == null || userId == null) {
            StreamRequestContextRegistry.SessionUser su =
                    resolvedTraceId != null ? streamRequestContextRegistry.resolve(resolvedTraceId) : null;
            if (su == null) {
                su = streamRequestContextRegistry.soleActiveSession();
            }
            if (su != null) {
                if (sessionId == null) {
                    sessionId = su.sessionId();
                }
                if (userId == null) {
                    userId = su.userId();
                }
            }
        }

        final String effSessionId = sessionId;
        final String effUserId = userId;
        final String effCurrentTurnUserMessage = currentTurnUserMessage;
        final Map<String, String> mdcCopy = mdcSnapshot;

        delegate.execute(() -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try {
                if (effSessionId != null && effUserId != null) {
                    AgentContextHolder.set(effSessionId, effUserId);
                    AgentContextHolder.setCurrentTurnUserMessage(effCurrentTurnUserMessage);
                }
                if (mdcCopy != null && !mdcCopy.isEmpty()) {
                    MDC.setContextMap(mdcCopy);
                } else if (resolvedTraceId != null) {
                    TraceUtil.setTraceId(resolvedTraceId);
                }
                command.run();
            } finally {
                AgentContextHolder.clear();
                if (previousMdc != null && !previousMdc.isEmpty()) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
            }
        });
    }
}
