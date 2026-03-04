package com.returensea.common.util;

import org.slf4j.MDC;

import java.util.UUID;

public class TraceUtil {
    
    public static final String TRACE_ID = "traceId";
    
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }
    
    public static void setTraceId(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            traceId = generateTraceId();
        }
        MDC.put(TRACE_ID, traceId);
    }
    
    public static void clear() {
        MDC.remove(TRACE_ID);
    }
}
