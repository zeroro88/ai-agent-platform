package com.returensea.common.constants;

public class AgentConstants {
    public static final String DEFAULT_SESSION_LANGUAGE = "zh-CN";
    public static final int MAX_ITERATIONS = 5;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MAX_CONVERSATION_TURNS = 20;

    public static final String MEMORY_KEY_PREFIX = "agent:memory:";
    public static final String SESSION_KEY_PREFIX = "agent:session:";
    public static final String USER_PROFILE_KEY_PREFIX = "agent:profile:";

    public static final int WORKING_MEMORY_SIZE = 1024 * 1024;
    public static final int SESSION_MEMORY_TTL_MINUTES = 30;
    public static final int SESSION_MEMORY_SIZE = 10 * 1024;

    public static final double LOW_CONFIDENCE_THRESHOLD = 0.6;
    public static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.8;

    public static final String TRACE_ID_HEADER = "X-Trace-ID";
    public static final String SESSION_ID_HEADER = "X-Session-ID";
    public static final String USER_ID_HEADER = "X-User-ID";

    private AgentConstants() {
    }
}
