package com.returensea.common.exception;

import lombok.Getter;

@Getter
public class AgentException extends RuntimeException {
    private final String errorCode;
    private final String details;

    public AgentException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    public AgentException(String errorCode, String message, String details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public AgentException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    public static AgentException toolNotFound(String toolName) {
        return new AgentException("TOOL_NOT_FOUND", "Tool not found: " + toolName);
    }

    public static AgentException permissionDenied(String action) {
        return new AgentException("PERMISSION_DENIED", "Permission denied for action: " + action);
    }

    public static AgentException routingFailed(String reason) {
        return new AgentException("ROUTING_FAILED", "Intent routing failed: " + reason);
    }

    public static AgentException llmError(String reason) {
        return new AgentException("LLM_ERROR", "LLM error: " + reason);
    }

    public static AgentException memoryError(String reason) {
        return new AgentException("MEMORY_ERROR", "Memory operation failed: " + reason);
    }
}
