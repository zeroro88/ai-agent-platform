package com.returensea.agent.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.IntentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.AgentResponse;
import com.returensea.common.util.TraceUtil;
import com.returensea.agent.agent.ActivityAgent;
import com.returensea.agent.agent.DomainAgent;
import com.returensea.agent.context.AgentContextHolder;
import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.config.TaskGraphTemplateProperties;
import com.returensea.agent.slot.SlotFillingService;
import com.returensea.agent.tool.ToolCenter;
import com.returensea.common.model.SlotFillingRequest;
import com.returensea.common.model.SlotFillingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorImpl implements Orchestrator {

    @Value("${ai.agent.max-iterations:5}")
    private int maxIterations;
    @Value("${ai.agent.timeout-seconds:30}")
    private int timeoutSeconds;

    private static final Set<String> SLOT_REQUIRED_INTENTS = Set.of("ACTIVITY_REGISTER");

    private final Map<String, DomainAgent> agentMap;
    private final MemoryService memoryService;
    private final SlotFillingService slotFillingService;
    private final ToolCenter toolCenter;
    private final ObjectMapper objectMapper;
    private final TaskGraphTemplateProperties taskGraphTemplate;

    @Override
    public AgentResponse process(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        String traceId = request.getTraceId();
        if (traceId == null || traceId.isEmpty()) {
            traceId = TraceUtil.generateTraceId();
        }
        TraceUtil.setTraceId(traceId);

        List<String> steps = new ArrayList<>();
        try {
            log.info("[{}] Processing request: {}", TraceUtil.getTraceId(), request.getMessage());
            AgentContextHolder.set(request.getSessionId(), request.getUserId());

            memoryService.updateWorkingMemory(request.getSessionId(), request.getUserId(), request.getMessage());

            steps.add("意图识别");
            String intentType = getIntentTypeFromRequest(request);
            if (request.getAgentType() == null && intentType != null) {
                request.setAgentType(inferAgentTypeFromIntent(intentType));
            }
            if (request.getAgentType() == null) {
                request.setAgentType(AgentType.ACTIVITY);
            }
            if (request.getRequiredPermission() == null) {
                request.setRequiredPermission(PermissionLevel.L0);
            }
            AgentRequest requestForGraph = request;
            if (intentType != null && SLOT_REQUIRED_INTENTS.contains(intentType)) {
                steps.add("槽位检查");
                SlotFillingResult slotResult = runSlotFilling(request, intentType);
                if (!slotResult.isComplete()) {
                    steps.add("等待补全信息");
                    memoryService.putSlotState(request.getSessionId(), request.getUserId(), intentType, slotResult.getFilledSlots());
                    String text = slotResult.getClarificationMessage() != null ? slotResult.getClarificationMessage() : "请补充必要信息。";
                    return AgentResponse.builder()
                            .responseId(UUID.randomUUID().toString())
                            .traceId(TraceUtil.getTraceId())
                            .sessionId(request.getSessionId())
                            .userId(request.getUserId())
                            .agentType(request.getAgentType())
                            .usedPermission(request.getRequiredPermission())
                            .content(buildContentJson(text, "clarification"))
                            .timestamp(LocalDateTime.now())
                            .processTimeMs(System.currentTimeMillis() - startTime)
                            .processingSteps(steps)
                            .build();
                }
                steps.add("槽位已齐");
                memoryService.clearSlotState(request.getSessionId(), request.getUserId(), intentType);
                Map<String, Object> enrichedContext = new HashMap<>(request.getContext() != null ? request.getContext() : Map.of());
                enrichedContext.putAll(slotResult.getFilledSlots());
                requestForGraph = AgentRequest.builder()
                        .sessionId(request.getSessionId())
                        .userId(request.getUserId())
                        .message(request.getMessage())
                        .agentType(request.getAgentType())
                        .requiredPermission(request.getRequiredPermission())
                        .context(enrichedContext)
                        .slots(request.getSlots())
                        .timestamp(request.getTimestamp())
                        .traceId(request.getTraceId())
                        .build();
            }

            steps.add("构建任务图");
            TaskGraph taskGraph = buildTaskGraph(requestForGraph);
            log.debug("[{}] Built task graph with {} nodes", TraceUtil.getTraceId(), taskGraph.getAllNodes().size());

            steps.add("执行任务图");
            AgentResponse response = executeTaskGraph(taskGraph);
            steps.add("Agent 处理完成");
            if (response.getAgentType() == null) response.setAgentType(request.getAgentType());
            if (response.getUsedPermission() == null) response.setUsedPermission(request.getRequiredPermission());

            response.setProcessTimeMs(System.currentTimeMillis() - startTime);
            response.setTraceId(TraceUtil.getTraceId());
            response.setProcessingSteps(steps);
            if (AgentContextHolder.getErrorDetail() != null) {
                response.setErrorDetail(AgentContextHolder.getErrorDetail());
                AgentContextHolder.clearErrorDetail();
            }
            memoryService.saveToSessionMemory(request.getSessionId(), request.getUserId(), request.getMessage(), extractTextFromContent(response.getContent()));

            log.info("[{}] Completed request, processTime={}ms", TraceUtil.getTraceId(), response.getProcessTimeMs());

            return response;
        } catch (Exception e) {
            log.error("[{}] Error processing request: {}", TraceUtil.getTraceId(), e.getMessage(), e);
            steps.add("处理异常");
            String detail = buildErrorDetail(e);
            AgentResponse err = buildErrorResponse(request, e.getMessage(), System.currentTimeMillis() - startTime);
            err.setErrorDetail(detail);
            err.setProcessingSteps(steps);
            return err;
        } finally {
            AgentContextHolder.clear();
            TraceUtil.clear();
        }
    }

    @Override
    public void processStream(AgentRequest request, Consumer<String> contentSink, Consumer<AgentResponse> onComplete) {
        long startTime = System.currentTimeMillis();
        String traceId = request.getTraceId();
        if (traceId == null || traceId.isEmpty()) {
            traceId = TraceUtil.generateTraceId();
        }
        TraceUtil.setTraceId(traceId);
        List<String> steps = new ArrayList<>();
        try {
            log.info("[{}] Processing stream request: {}", TraceUtil.getTraceId(), request.getMessage());
            AgentContextHolder.set(request.getSessionId(), request.getUserId());
            memoryService.updateWorkingMemory(request.getSessionId(), request.getUserId(), request.getMessage());

            steps.add("意图识别");
            String intentType = getIntentTypeFromRequest(request);
            if (request.getAgentType() == null && intentType != null) {
                request.setAgentType(inferAgentTypeFromIntent(intentType));
            }
            if (request.getAgentType() == null) {
                request.setAgentType(AgentType.ACTIVITY);
            }
            if (request.getRequiredPermission() == null) {
                request.setRequiredPermission(PermissionLevel.L0);
            }
            AgentRequest requestForGraph = request;
            if (intentType != null && SLOT_REQUIRED_INTENTS.contains(intentType)) {
                steps.add("槽位检查");
                SlotFillingResult slotResult = runSlotFilling(request, intentType);
                if (!slotResult.isComplete()) {
                    steps.add("等待补全信息");
                    memoryService.putSlotState(request.getSessionId(), request.getUserId(), intentType, slotResult.getFilledSlots());
                    String text = slotResult.getClarificationMessage() != null ? slotResult.getClarificationMessage() : "请补充必要信息。";
                    AgentResponse early = AgentResponse.builder()
                            .responseId(UUID.randomUUID().toString())
                            .traceId(TraceUtil.getTraceId())
                            .sessionId(request.getSessionId())
                            .userId(request.getUserId())
                            .agentType(request.getAgentType())
                            .usedPermission(request.getRequiredPermission())
                            .content(buildContentJson(text, "clarification"))
                            .timestamp(LocalDateTime.now())
                            .processTimeMs(System.currentTimeMillis() - startTime)
                            .processingSteps(steps)
                            .build();
                    onComplete.accept(early);
                    return;
                }
                steps.add("槽位已齐");
                memoryService.clearSlotState(request.getSessionId(), request.getUserId(), intentType);
                Map<String, Object> enrichedContext = new HashMap<>(request.getContext() != null ? request.getContext() : Map.of());
                enrichedContext.putAll(slotResult.getFilledSlots());
                requestForGraph = AgentRequest.builder()
                        .sessionId(request.getSessionId())
                        .userId(request.getUserId())
                        .message(request.getMessage())
                        .agentType(request.getAgentType())
                        .requiredPermission(request.getRequiredPermission())
                        .context(enrichedContext)
                        .slots(request.getSlots())
                        .timestamp(request.getTimestamp())
                        .traceId(request.getTraceId())
                        .build();
            }

            steps.add("构建任务图");
            TaskGraph taskGraph = buildTaskGraph(requestForGraph);
            steps.add("执行任务图");
            AgentResponse response = executeTaskGraphStreaming(taskGraph, contentSink);
            steps.add("Agent 处理完成");
            if (response.getAgentType() == null) response.setAgentType(request.getAgentType());
            if (response.getUsedPermission() == null) response.setUsedPermission(request.getRequiredPermission());
            response.setProcessTimeMs(System.currentTimeMillis() - startTime);
            response.setTraceId(TraceUtil.getTraceId());
            response.setProcessingSteps(steps);
            if (AgentContextHolder.getErrorDetail() != null) {
                response.setErrorDetail(AgentContextHolder.getErrorDetail());
                AgentContextHolder.clearErrorDetail();
            }
            memoryService.saveToSessionMemory(request.getSessionId(), request.getUserId(), request.getMessage(), extractTextFromContent(response.getContent()));
            onComplete.accept(response);
        } catch (Exception e) {
            log.error("[{}] Error in processStream: {}", TraceUtil.getTraceId(), e.getMessage(), e);
            steps.add("处理异常");
            AgentResponse err = buildErrorResponse(request, e.getMessage(), System.currentTimeMillis() - startTime);
            err.setErrorDetail(buildErrorDetail(e));
            err.setProcessingSteps(steps);
            onComplete.accept(err);
        } finally {
            AgentContextHolder.clear();
            TraceUtil.clear();
        }
    }

    /**
     * 与 executeTaskGraph 类似，但对 ACTIVITY 的 process 节点使用流式输出。
     */
    private AgentResponse executeTaskGraphStreaming(TaskGraph taskGraph, Consumer<String> contentSink) {
        taskGraph.setStatus(TaskGraph.TaskStatus.RUNNING);
        Map<String, TaskGraph.TaskNode> nodeMap = taskGraph.getAllNodes().stream()
                .collect(Collectors.toMap(TaskGraph.TaskNode::getNodeId, n -> n));
        List<TaskGraph.TaskNode> sortedNodes = sortByDependencies(taskGraph.getAllNodes());
        StringBuilder finalResponse = new StringBuilder();
        TaskGraph.TaskNode lastExecutedAgentNode = null;

        for (TaskGraph.TaskNode node : sortedNodes) {
            if (node.getStatus() == TaskGraph.TaskNode.NodeStatus.COMPLETED) continue;
            if (!areDependenciesMet(node, nodeMap)) {
                log.warn("[{}] Skipping node {} - dependencies not met", TraceUtil.getTraceId(), node.getNodeId());
                node.setStatus(TaskGraph.TaskNode.NodeStatus.SKIPPED);
                continue;
            }
            node.setStatus(TaskGraph.TaskNode.NodeStatus.RUNNING);
            try {
                Object result;
                if ("process".equals(node.getAction()) && node.getAgentType() != null && "ACTIVITY".equals(node.getAgentType())) {
                    DomainAgent agent = agentMap.get(node.getAgentType());
                    if (agent instanceof ActivityAgent) {
                        StringBuilder accumulated = new StringBuilder();
                        ((ActivityAgent) agent).streamProcess(buildAgentRequestFromNode(node, taskGraph), token -> {
                            contentSink.accept(token);
                            accumulated.append(token);
                        });
                        result = accumulated.toString();
                    } else {
                        result = executeAgentNode(node, taskGraph);
                    }
                } else {
                    result = executeNode(node, taskGraph);
                }
                node.setResult(Map.of("result", result));
                node.setStatus(TaskGraph.TaskNode.NodeStatus.COMPLETED);
                if ("process".equals(node.getAction()) && node.getAgentType() != null && !"SYSTEM".equals(node.getAgentType())) {
                    lastExecutedAgentNode = node;
                }
                if (result instanceof String) {
                    if (finalResponse.length() > 0) finalResponse.append("\n\n");
                    finalResponse.append(result);
                }
                taskGraph.getContext().put(node.getNodeId(), result);
            } catch (Exception e) {
                log.error("[{}] Error executing node {}: {}", TraceUtil.getTraceId(), node.getNodeId(), e.getMessage(), e);
                node.setStatus(TaskGraph.TaskNode.NodeStatus.FAILED);
                taskGraph.setStatus(TaskGraph.TaskStatus.FAILED);
                throw new RuntimeException(e);
            }
        }
        taskGraph.setStatus(TaskGraph.TaskStatus.COMPLETED);
        taskGraph.setCompletedAt(LocalDateTime.now());
        String normalized = normalizeContentForResponse(finalResponse.toString());
        AgentResponse.AgentResponseBuilder builder = AgentResponse.builder()
                .responseId(UUID.randomUUID().toString())
                .traceId(TraceUtil.getTraceId())
                .sessionId(taskGraph.getSessionId())
                .userId(taskGraph.getUserId())
                .content(buildContentJson(normalized, "message"))
                .timestamp(LocalDateTime.now());
        if (lastExecutedAgentNode != null) {
            try {
                builder.agentType(AgentType.valueOf(lastExecutedAgentNode.getAgentType()));
                Object perm = lastExecutedAgentNode.getParams().get("permission");
                if (perm instanceof PermissionLevel) {
                    builder.usedPermission((PermissionLevel) perm);
                }
            } catch (Exception ignored) {}
        }
        return builder.build();
    }

    private AgentRequest buildAgentRequestFromNode(TaskGraph.TaskNode node, TaskGraph taskGraph) {
        return AgentRequest.builder()
                .sessionId(taskGraph.getSessionId())
                .userId(taskGraph.getUserId())
                .message((String) node.getParams().get("message"))
                .agentType(AgentType.valueOf(node.getAgentType()))
                .requiredPermission((PermissionLevel) node.getParams().getOrDefault("permission", PermissionLevel.L0))
                .context(taskGraph.getContext())
                .timestamp(LocalDateTime.now())
                .traceId(TraceUtil.getTraceId())
                .build();
    }

    @Override
    public TaskGraph buildTaskGraph(AgentRequest request) {
        List<TaskGraph.TaskNode> allNodes = new ArrayList<>();
        TaskGraph.TaskNode rootNode = null;
        String intentType = request.getContext() != null && request.getContext().get("intentType") != null
                ? request.getContext().get("intentType").toString() : null;
        List<TaskGraphTemplateProperties.StepDef> steps = taskGraphTemplate.getStepsForIntent(intentType);

        for (TaskGraphTemplateProperties.StepDef step : steps) {
            String agentTypeStr = step.getAgentType();
            if (taskGraphTemplate.isFromRequest(agentTypeStr)) {
                if (request.getAgentType() == null) continue;
                agentTypeStr = request.getAgentType().name();
            }
            Map<String, Object> params = buildParamsForAction(step.getAction(), request);
            TaskGraph.TaskNode node = TaskGraph.TaskNode.builder()
                    .nodeId(step.getNodeId())
                    .agentType(agentTypeStr)
                    .action(step.getAction())
                    .params(params)
                    .status(TaskGraph.TaskNode.NodeStatus.PENDING)
                    .order(step.getOrder())
                    .dependencies(step.getDependencies() != null ? step.getDependencies() : List.of())
                    .build();
            if (rootNode == null) rootNode = node;
            allNodes.add(node);
        }

        return TaskGraph.builder()
                .taskId(UUID.randomUUID().toString())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .originalMessage(request.getMessage())
                .rootNode(rootNode != null ? rootNode : (allNodes.isEmpty() ? null : allNodes.get(0)))
                .allNodes(allNodes)
                .context(new HashMap<>())
                .status(TaskGraph.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Map<String, Object> buildParamsForAction(String action, AgentRequest request) {
        if ("analyze_intent".equals(action)) {
            return Map.of("message", request.getMessage() != null ? request.getMessage() : "");
        }
        if ("process".equals(action)) {
            Map<String, Object> params = new HashMap<>();
            params.put("message", request.getMessage() != null ? request.getMessage() : "");
            params.put("context", request.getContext() != null ? request.getContext() : new HashMap<>());
            params.put("permission", request.getRequiredPermission() != null ? request.getRequiredPermission() : PermissionLevel.L0);
            return params;
        }
        return Map.of();
    }

    @Override
    public AgentResponse executeTaskGraph(TaskGraph taskGraph) {
        taskGraph.setStatus(TaskGraph.TaskStatus.RUNNING);
        
        Map<String, TaskGraph.TaskNode> nodeMap = taskGraph.getAllNodes().stream()
                .collect(Collectors.toMap(TaskGraph.TaskNode::getNodeId, n -> n));
        
        List<TaskGraph.TaskNode> sortedNodes = sortByDependencies(taskGraph.getAllNodes());
        
        StringBuilder finalResponse = new StringBuilder();
        TaskGraph.TaskNode lastExecutedAgentNode = null;

        for (TaskGraph.TaskNode node : sortedNodes) {
            if (node.getStatus() == TaskGraph.TaskNode.NodeStatus.COMPLETED) {
                continue;
            }
            
            if (!areDependenciesMet(node, nodeMap)) {
                log.warn("[{}] Skipping node {} - dependencies not met", TraceUtil.getTraceId(), node.getNodeId());
                node.setStatus(TaskGraph.TaskNode.NodeStatus.SKIPPED);
                continue;
            }
            
            node.setStatus(TaskGraph.TaskNode.NodeStatus.RUNNING);
            log.debug("[{}] Executing node: {} action={}", TraceUtil.getTraceId(), node.getNodeId(), node.getAction());
            
            try {
                Object result = executeNode(node, taskGraph);
                node.setResult(Map.of("result", result));
                node.setStatus(TaskGraph.TaskNode.NodeStatus.COMPLETED);
                if ("process".equals(node.getAction()) && node.getAgentType() != null && !"SYSTEM".equals(node.getAgentType())) {
                    lastExecutedAgentNode = node;
                }
                if (result instanceof String) {
                    if (finalResponse.length() > 0) {
                        finalResponse.append("\n\n");
                    }
                    finalResponse.append(result);
                }
                
                taskGraph.getContext().put(node.getNodeId(), result);
                
            } catch (Exception e) {
                log.error("[{}] Error executing node {}: {}", TraceUtil.getTraceId(), node.getNodeId(), e.getMessage(), e);
                node.setStatus(TaskGraph.TaskNode.NodeStatus.FAILED);
                taskGraph.setStatus(TaskGraph.TaskStatus.FAILED);
                return buildErrorResponse(
                        AgentRequest.builder()
                            .sessionId(taskGraph.getSessionId())
                            .userId(taskGraph.getUserId())
                            .build(),
                        e.getMessage(),
                        0
                );
            }
        }

        taskGraph.setStatus(TaskGraph.TaskStatus.COMPLETED);
        taskGraph.setCompletedAt(LocalDateTime.now());

        AgentResponse.AgentResponseBuilder builder = AgentResponse.builder()
                .responseId(UUID.randomUUID().toString())
                .traceId(TraceUtil.getTraceId())
                .sessionId(taskGraph.getSessionId())
                .userId(taskGraph.getUserId())
                .content(buildContentJson(normalizeContentForResponse(finalResponse.toString()), "message"))
                .timestamp(LocalDateTime.now());
        if (lastExecutedAgentNode != null) {
            try {
                builder.agentType(AgentType.valueOf(lastExecutedAgentNode.getAgentType()));
                Object perm = lastExecutedAgentNode.getParams().get("permission");
                if (perm instanceof PermissionLevel) {
                    builder.usedPermission((PermissionLevel) perm);
                }
            } catch (Exception e) {
                log.debug("Could not set agentType/usedPermission on response: {}", e.getMessage());
            }
        }
        return builder.build();
    }

    /** 从拼接结果中剔除内部输出，使 content 仅为面向用户的纯文本，便于作为标准 JSON 的 string 值 */
    private String normalizeContentForResponse(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String s = raw.replaceAll("(?m)^Intent analyzed:[^\n]*\n?\n?", "").trim();
        return java.util.Arrays.stream(s.split("\n"))
                .filter(line -> {
                    String t = line.trim();
                    if (t.isEmpty()) return true;
                    if (t.matches("^\\w+,\\s*$")) return false;
                    if (t.contains("\"name\"") && t.contains("\"arguments\"")) return false;
                    return true;
                })
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private List<TaskGraph.TaskNode> sortByDependencies(List<TaskGraph.TaskNode> nodes) {
        return nodes.stream()
                .sorted(Comparator.comparingInt(TaskGraph.TaskNode::getOrder))
                .collect(Collectors.toList());
    }

    private boolean areDependenciesMet(TaskGraph.TaskNode node, Map<String, TaskGraph.TaskNode> nodeMap) {
        if (node.getDependencies() == null || node.getDependencies().isEmpty()) {
            return true;
        }
        
        for (String depId : node.getDependencies()) {
            TaskGraph.TaskNode depNode = nodeMap.get(depId);
            if (depNode == null || depNode.getStatus() != TaskGraph.TaskNode.NodeStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private Object executeNode(TaskGraph.TaskNode node, TaskGraph taskGraph) {
        if ("SYSTEM".equals(node.getAgentType())) {
            return "Intent analyzed: " + taskGraph.getOriginalMessage();
        }

        if ("process".equals(node.getAction())) {
            return executeAgentNode(node, taskGraph);
        }
        
        return "Unknown action: " + node.getAction();
    }

    private Object executeAgentNode(TaskGraph.TaskNode node, TaskGraph taskGraph) {
        AgentType agentType = AgentType.valueOf(node.getAgentType());
        DomainAgent agent = agentMap.get(agentType.name());
        
        if (agent == null) {
            throw new RuntimeException("Agent not found: " + agentType);
        }

        PermissionLevel permission = (PermissionLevel) node.getParams().getOrDefault("permission", PermissionLevel.L0);
        
        AgentRequest agentRequest = AgentRequest.builder()
                .sessionId(taskGraph.getSessionId())
                .userId(taskGraph.getUserId())
                .message((String) node.getParams().get("message"))
                .agentType(agentType)
                .requiredPermission(permission)
                .context(taskGraph.getContext())
                .timestamp(LocalDateTime.now())
                .traceId(TraceUtil.getTraceId())
                .build();

        AgentResponse response = agent.process(agentRequest);
        
        return response.getContent();
    }

    @Override
    public List<TaskGraph.TaskNode> planExecution(TaskGraph taskGraph) {
        return taskGraph.getAllNodes().stream()
                .sorted(Comparator.comparingInt(TaskGraph.TaskNode::getOrder))
                .collect(Collectors.toList());
    }

    private String getIntentTypeFromRequest(AgentRequest request) {
        if (request.getContext() == null) return null;
        Object v = request.getContext().get("intentType");
        return v != null ? v.toString() : null;
    }

    private SlotFillingResult runSlotFilling(AgentRequest request, String intentType) {
        Map<String, Object> currentSlots = memoryService.getSlotState(request.getSessionId(), request.getUserId(), intentType).orElse(null);
        SlotFillingRequest slotReq = SlotFillingRequest.builder()
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .intentType(intentType)
                .userMessage(request.getMessage())
                .currentSlots(currentSlots)
                .build();
        return slotFillingService.fillSlots(slotReq);
    }

    private AgentResponse buildErrorResponse(AgentRequest request, String error, long processTime) {
        return AgentResponse.builder()
                .responseId(UUID.randomUUID().toString())
                .traceId(TraceUtil.getTraceId())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .agentType(request.getAgentType())
                .usedPermission(request.getRequiredPermission())
                .content(buildContentJson("抱歉，处理您的请求时出现错误：" + error, "error"))
                .error(error)
                .timestamp(LocalDateTime.now())
                .processTimeMs(processTime)
                .build();
    }

    /** 从 context 的 intentType 推断 AgentType，用于请求未带 agentType 时补全 */
    private AgentType inferAgentTypeFromIntent(String intentTypeStr) {
        if (intentTypeStr == null) return AgentType.ACTIVITY;
        try {
            IntentType it = IntentType.valueOf(intentTypeStr);
            switch (it) {
                case POLICY_CONSULT: return AgentType.POLICY;
                case OPERATION: return AgentType.OPERATION;
                default: return AgentType.ACTIVITY;
            }
        } catch (Exception e) {
            return AgentType.ACTIVITY;
        }
    }

    /** 将面向用户的文本封装为 JSON 字符串，便于前端按结构解析 */
    private String buildContentJson(String text, String type) {
        if (text == null) text = "";
        try {
            return objectMapper.writeValueAsString(Map.of("text", text, "type", type != null ? type : "message"));
        } catch (JsonProcessingException e) {
            log.warn("Failed to build content JSON, falling back to plain text: {}", e.getMessage());
            return text;
        }
    }

    /** 从 content（可能为 JSON 字符串）中提取展示用纯文本，用于会话记忆等 */
    private String extractTextFromContent(String content) {
        if (content == null || content.isEmpty()) return content;
        if (!content.trim().startsWith("{")) return content;
        try {
            Map<String, Object> map = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            Object text = map.get("text");
            return text != null ? text.toString() : content;
        } catch (Exception e) {
            return content;
        }
    }

    /** 生成可复制的错误详情（异常类型 + 消息 + 堆栈前若干行），便于调试窗口复制 */
    private String buildErrorDetail(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String stack = sw.toString();
        int lineCount = 0;
        int maxLines = 25;
        for (String line : stack.split("\n")) {
            if (lineCount >= maxLines) {
                sb.append("  ...\n");
                break;
            }
            sb.append(line).append("\n");
            lineCount++;
        }
        return sb.toString();
    }
}
