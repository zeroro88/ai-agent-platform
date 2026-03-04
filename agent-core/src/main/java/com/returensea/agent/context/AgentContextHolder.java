package com.returensea.agent.context;

/**
 * 当前请求的会话上下文，供 Tool、槽位填充等读取 sessionId/userId（如存储最近活动列表、解析「1」为第一个活动）。
 */
public final class AgentContextHolder {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    /** 工具层失败时写入，供响应带出 errorDetail 便于调试页复制 */
    private static final ThreadLocal<String> ERROR_DETAIL = new ThreadLocal<>();

    public static void set(String sessionId, String userId) {
        SESSION_ID.set(sessionId);
        USER_ID.set(userId);
        ERROR_DETAIL.remove();
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void setErrorDetail(String detail) {
        ERROR_DETAIL.set(detail);
    }

    public static String getErrorDetail() {
        return ERROR_DETAIL.get();
    }

    public static void clearErrorDetail() {
        ERROR_DETAIL.remove();
    }

    public static void clear() {
        SESSION_ID.remove();
        USER_ID.remove();
        ERROR_DETAIL.remove();
    }
}
