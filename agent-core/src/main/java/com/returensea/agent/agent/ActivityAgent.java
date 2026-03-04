package com.returensea.agent.agent;

import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.tool.ToolCenter;
import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.model.AgentRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Component("ACTIVITY")
public class ActivityAgent extends AbstractDomainAgent {
    private static final String CREATE_DRAFT_PREFIX = "CREATE_DRAFT|";
    private static final String CREATE_DRAFT_DONE = "CREATE_DRAFT_DONE";

    interface ActivityAssistant {
        @SystemMessage(SYSTEM_PROMPT)
        String chat(@UserMessage String userMessage);
    }

    interface ActivityAssistantStreaming {
        @SystemMessage(SYSTEM_PROMPT)
        TokenStream chat(@UserMessage String userMessage);
    }

    private static final String SYSTEM_PROMPT = """
            你是海归服务平台的活动助手，负责推荐活动、引导报名和查询状态。
            
            你的职责：
            1. 根据用户的城市、兴趣推荐合适的活动。
            2. 引导用户完成报名信息的填写（姓名、电话等）。
            3. 查询用户的报名状态。
            
            工具使用规则：
            - 当用户想找活动时，调用 searchActivities 工具。
            - 当用户想报名时，先收集信息，齐全后调用 registerActivity 工具。
            - 当用户查状态时，调用 queryOrder 工具。
            - 当用户想发起/创建/发布活动时，先补齐标题、城市、日期，齐全后调用 createActivity 工具。
            - 如果用户消息较短（如“上海，明天”），先结合最近对话理解意图，不要直接改成搜索活动。
            
            回答风格：
            - 热情、专业、简洁。
            - 如果没有找到活动，请建议用户尝试其他城市或时间。
            """;

    private final ActivityAssistant assistant;
    private final ActivityAssistantStreaming streamingAssistant;
    private final MemoryService memoryService;
    private final ToolCenter toolCenter;

    public ActivityAgent(ChatModel chatLanguageModel,
                         StreamingChatModel streamingChatLanguageModel,
                         ToolCenter toolCenter,
                         MemoryService memoryService) {
        this.memoryService = memoryService;
        this.toolCenter = toolCenter;
        this.assistant = AiServices.builder(ActivityAssistant.class)
                .chatModel(chatLanguageModel)
                .tools(toolCenter)
                .build();
        this.streamingAssistant = AiServices.builder(ActivityAssistantStreaming.class)
                .streamingChatModel(streamingChatLanguageModel)
                .tools(toolCenter)
                .build();
    }

    @Override
    protected AgentType getAgentType() {
        return AgentType.ACTIVITY;
    }

    @Override
    protected String processInternal(AgentRequest request) {
        log.info("ActivityAgent processing request: {}", request.getMessage());
        try {
            String createResult = tryHandleCreateContinuation(request);
            if (createResult != null) {
                return createResult;
            }
            String contextAwareMessage = buildContextAwareMessage(request);
            return assistant.chat(contextAwareMessage);
        } catch (Exception e) {
            log.error("Error in ActivityAgent LLM processing", e);
            return "抱歉，我现在有点忙，请稍后再试。（LLM 调用失败）";
        }
    }

    private static final long STREAM_WAIT_TIMEOUT_MINUTES = 2;

    /**
     * 流式处理：每生成一段内容就通过 contentSink 推送（LangChain4j 1.x 已支持 Ollama 流式 + tools）。
     * 会阻塞直到流结束或超时，避免 SSE 在未收到内容前就完成。
     */
    public void streamProcess(AgentRequest request, Consumer<String> contentSink) {
        log.info("ActivityAgent streaming request: {}", request.getMessage());
        try {
            String createResult = tryHandleCreateContinuation(request);
            if (createResult != null) {
                contentSink.accept(createResult);
                return;
            }
            String contextAwareMessage = buildContextAwareMessage(request);
            TokenStream stream = streamingAssistant.chat(contextAwareMessage);
            CountDownLatch done = new CountDownLatch(1);
            stream
                    .onPartialResponse(contentSink)
                    .onCompleteResponse(r -> done.countDown())
                    .onError(e -> {
                        log.error("ActivityAgent streaming error", e);
                        contentSink.accept("抱歉，我现在有点忙，请稍后再试。（流式调用异常）");
                        done.countDown();
                    })
                    .start();
            if (!done.await(STREAM_WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                log.warn("ActivityAgent streaming wait timed out");
            }
        } catch (Exception e) {
            log.error("Error in ActivityAgent streaming", e);
            contentSink.accept("抱歉，我现在有点忙，请稍后再试。（LLM 调用失败）");
        }
    }

    private String tryHandleCreateContinuation(AgentRequest request) {
        String message = request.getMessage();
        if (isCreateConfirmation(message)) {
            Map<String, String> draft = getLatestCreateDraft(request);
            if (draft == null) {
                return "当前没有待确认的活动草稿。请先提供活动标题、城市和日期。";
            }
            Map<String, Object> params = Map.of(
                    "title", draft.getOrDefault("title", "用户发起活动"),
                    "city", draft.getOrDefault("city", "上海"),
                    "date", draft.getOrDefault("date", "明天"),
                    "location", draft.getOrDefault("location", "待定场地"),
                    "description", draft.getOrDefault("description", "用户发起活动")
            );
            String result = String.valueOf(toolCenter.executeTool("createActivity", params, PermissionLevel.L1));
            memoryService.updateWorkingMemory(request.getSessionId(), request.getUserId(), CREATE_DRAFT_DONE);
            return result;
        }
        if (!looksLikeCreateDetails(message) || !hasRecentCreateIntent(request)) {
            return null;
        }
        String city = extractCity(message);
        String date = extractDate(message);
        String title = extractTitle(message, city, date);
        if (title.isEmpty()) {
            return "我已经识别到您在补充发起活动信息，请再提供活动标题，我就可以立即为您发起。";
        }
        if (city.isEmpty() || date.isEmpty()) {
            return "要帮您发起活动，我还需要城市和日期信息，例如“上海，明天，海归创业交流会”。";
        }
        String location = city + "待定场地";
        String description = "用户发起活动";
        memoryService.updateWorkingMemory(
                request.getSessionId(),
                request.getUserId(),
                CREATE_DRAFT_PREFIX + "title=" + title + "|city=" + city + "|date=" + date + "|location=" + location + "|description=" + description
        );
        return """
                我已整理好活动草稿，请您确认后我再发起：
                - 标题：%s
                - 城市：%s
                - 时间：%s 14:00
                - 地点：%s
                
                请回复“确认发起”或“取消”。
                """.formatted(title, city, date, location);
    }

    private String buildContextAwareMessage(AgentRequest request) {
        String runtimeContext = request.getContext() == null || request.getContext().isEmpty()
                ? ""
                : "路由上下文：" + request.getContext() + "\n";
        String history = memoryService.getSessionMemory(request.getSessionId(), request.getUserId())
                .map(this::renderRecentHistory)
                .orElse("");
        if (history.isEmpty()) {
            return runtimeContext + "当前用户输入：" + request.getMessage();
        }
        return runtimeContext + "最近对话如下：\n" + history + "\n当前用户输入：" + request.getMessage();
    }

    private String renderRecentHistory(List<Map<String, Object>> history) {
        return history.stream()
                .skip(Math.max(0, history.size() - 3))
                .map(item -> "用户：" + safe(item.get("user")) + "\n助手：" + safe(item.get("assistant")))
                .collect(Collectors.joining("\n"));
    }

    private boolean hasRecentCreateIntent(AgentRequest request) {
        Set<String> createKeywords = Set.of("创建活动", "发起活动", "发布活动", "办活动", "搞活动");
        return memoryService.getSessionMemory(request.getSessionId(), request.getUserId())
                .map(history -> history.stream()
                        .skip(Math.max(0, history.size() - 3))
                        .map(item -> safe(item.get("user")))
                        .anyMatch(text -> createKeywords.stream().anyMatch(text::contains)))
                .orElse(false);
    }

    private boolean looksLikeCreateDetails(String message) {
        return message.contains("，") || message.contains(",") || message.contains("明天") || message.contains("后天");
    }

    private boolean isCreateConfirmation(String message) {
        return message.contains("确认发起")
                || message.contains("确认创建")
                || message.equals("确认")
                || message.equals("发起")
                || message.equals("提交");
    }

    private Map<String, String> getLatestCreateDraft(AgentRequest request) {
        return memoryService.getWorkingMemory(request.getSessionId(), request.getUserId())
                .map(memory -> memory.get("history"))
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .map(history -> {
                    for (int i = history.size() - 1; i >= 0; i--) {
                        String entry = String.valueOf(history.get(i));
                        if (CREATE_DRAFT_DONE.equals(entry)) {
                            return null;
                        }
                        if (entry.startsWith(CREATE_DRAFT_PREFIX)) {
                            return parseDraft(entry);
                        }
                    }
                    return null;
                })
                .orElse(null);
    }

    private Map<String, String> parseDraft(String raw) {
        Map<String, String> result = new java.util.HashMap<>();
        String[] fields = raw.substring(CREATE_DRAFT_PREFIX.length()).split("\\|");
        for (String field : fields) {
            String[] kv = field.split("=", 2);
            if (kv.length == 2) {
                result.put(kv[0], kv[1]);
            }
        }
        return result;
    }

    private String extractCity(String message) {
        for (String city : List.of("北京", "上海", "深圳", "广州", "杭州", "成都", "武汉", "西安")) {
            if (message.contains(city)) {
                return city;
            }
        }
        return "";
    }

    private String extractDate(String message) {
        if (message.contains("明天")) return "明天";
        if (message.contains("后天")) return "后天";
        if (message.contains("今天")) return "今天";
        return "";
    }

    private String extractTitle(String message, String city, String date) {
        String normalized = message.replace("，", ",");
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals(city) || trimmed.equals(date) || trimmed.equals("好的")) continue;
            if (trimmed.length() >= 3) return trimmed;
        }
        String direct = message.replace(city, "").replace(date, "").replace("，", "").replace(",", "").trim();
        if (direct.startsWith("好的")) {
            direct = direct.substring(2).trim();
        }
        return direct;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
