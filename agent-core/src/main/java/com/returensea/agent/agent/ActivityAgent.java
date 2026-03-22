package com.returensea.agent.agent;

import com.returensea.agent.config.LangChainStreamingExecutorConfig;
import com.returensea.agent.context.AgentContextHolder;
import com.returensea.agent.guardrail.BannedContentInputGuardrail;
import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.tool.ToolCenter;
import com.returensea.agent.util.LastActivityIdsSupport;
import com.returensea.agent.util.RegistrationParsers;
import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.model.AgentRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
@Component("ACTIVITY")
public class ActivityAgent extends AbstractDomainAgent {
    private static final String CREATE_DRAFT_PREFIX = "CREATE_DRAFT|";
    private static final String CREATE_DRAFT_DONE = "CREATE_DRAFT_DONE";
    /** 拼进用户消息的最近轮次，避免刚搜过的活动/工具结果丢失 */
    private static final int RECENT_HISTORY_TURNS = 8;

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
            - 当用户想找/推荐活动时，只调用 searchActivities；不要主动建议用户“发起活动”或“创建活动”。
            - 当用户想报名/参加某活动时，先收集信息，齐全后调用 registerActivity；不要调用 createActivity。
            - 用户在同一轮或最近对话里已明确「要参加」并提供了姓名+手机号时，必须立即调用 registerActivity，禁止让用户逐字重复整段话或空洞确认。若用户仅回复「是的」「好的」「确认」等，且上一轮已留过姓名电话，视为确认报名，须立即 registerActivity，不得再次复述同一套确认话术。
            - 若本轮 searchActivities 只展示过一场活动且用户未指定 ID，registerActivity 的 activityId 用字符串 "1"（表示推荐列表第 1 条）。
            - searchActivities 返回的每条活动都带有「活动ID（报名 registerActivity 必填）」：请把该 ID 原样传给 registerActivity 的 activityId；也可用 1、2、3 表示本次推荐列表中的第几个。勿编造 act-xxx；严禁把活动标题里的长数字（如「-1774172424602」等时间戳后缀）当作 activityId。若用户只说城市/标题没有 ID，应先 searchActivities 或请用户从最近列表中选 ID。
            - 当用户查状态时，调用 queryOrder 工具。
            - 仅当用户明确说“发起活动”“创建活动”“办活动”“发布活动”“组织活动”时，才视为创建意图；先补齐标题、城市、日期，齐全后调用 createActivity。用户只说“参加”“推荐”“找活动”或只提供时间、地点、主题时，不得调用 createActivity。
            - 如果用户消息较短（如“上海，明天”），先结合最近对话理解意图，不要直接改成搜索活动。
            
            回答风格：
            - 热情、专业、简洁。
            - 如果没有找到活动，只建议用户尝试其他城市或时间，不要主动提议“可以发起一个活动”。
            - 严禁在同一条回复里编造多轮对话（例如「用户：」「助手：」来回演戏），严禁把同一段话或同一段错误说明重复粘贴多遍。
            - 每次调用工具后，只用一两句话向用户说明结果；若报名失败，简洁说明原因与下一步即可，不要模拟“再试一次”的多轮内心戏。
            """;

    private final ActivityAssistant assistant;
    private final ActivityAssistantStreaming streamingAssistant;
    private final MemoryService memoryService;
    private final ToolCenter toolCenter;

    public ActivityAgent(ChatModel chatLanguageModel,
                         StreamingChatModel streamingChatLanguageModel,
                         ToolCenter toolCenter,
                         MemoryService memoryService,
                         BannedContentInputGuardrail inputGuardrail,
                         @Qualifier(LangChainStreamingExecutorConfig.ACTIVITY_STREAMING_TOOL_EXECUTOR)
                         Executor activityStreamingToolExecutor) {
        this.memoryService = memoryService;
        this.toolCenter = toolCenter;
        this.assistant = AiServices.builder(ActivityAssistant.class)
                .chatModel(chatLanguageModel)
                .tools(toolCenter)
                .inputGuardrails(inputGuardrail)
                .build();
        this.streamingAssistant = AiServices.builder(ActivityAssistantStreaming.class)
                .streamingChatModel(streamingChatLanguageModel)
                .tools(toolCenter)
                .inputGuardrails(inputGuardrail)
                .executeToolsConcurrently(activityStreamingToolExecutor)
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
            String registerResult = tryHandleRegisterContinuation(request);
            if (registerResult != null) {
                return registerResult;
            }
            String createResult = tryHandleCreateContinuation(request);
            if (createResult != null) {
                return createResult;
            }
            String contextAwareMessage = buildContextAwareMessage(request);
            return assistant.chat(contextAwareMessage);
        } catch (InputGuardrailException e) {
            String msg = extractGuardrailBlockMessage(e.getMessage());
            log.warn("ActivityAgent input guardrail blocked: {}", msg);
            return msg;
        } catch (Exception e) {
            log.error("Error in ActivityAgent LLM processing", e);
            return "抱歉，我现在有点忙，请稍后再试。（LLM 调用失败）";
        }
    }

    private static final long STREAM_WAIT_TIMEOUT_MINUTES = 2;

    /**
     * 流式处理：通过 eventSink(type, payload) 推送，type 为 "contentDelta"（正常内容）或 "contentBanned"（护栏拦截）。
     * 会阻塞直到流结束或超时，避免 SSE 在未收到内容前就完成。
     */
    public void streamProcess(AgentRequest request, BiConsumer<String, String> eventSink) {
        long t0 = System.currentTimeMillis();
        log.info("ActivityAgent streaming request: {}", request.getMessage());
        // 流式链路可能在虚拟线程上执行，与 Orchestrator 设置 ThreadLocal 的线程不一致；此处保证本线程可见 session/userId，供工具线程快照。
        AgentContextHolder.set(request.getSessionId(), request.getUserId());
        try {
            String registerResult = tryHandleRegisterContinuation(request);
            if (registerResult != null) {
                eventSink.accept("contentDelta", registerResult);
                log.info("ActivityAgent stream short-circuit=registerActivity tookMs={}", System.currentTimeMillis() - t0);
                return;
            }
            String createResult = tryHandleCreateContinuation(request);
            if (createResult != null) {
                eventSink.accept("contentDelta", createResult);
                log.info("ActivityAgent stream short-circuit=createDraft tookMs={}", System.currentTimeMillis() - t0);
                return;
            }
            String contextAwareMessage = buildContextAwareMessage(request);
            TokenStream stream = streamingAssistant.chat(contextAwareMessage);
            CountDownLatch done = new CountDownLatch(1);
            stream
                    .onPartialResponse(chunk -> eventSink.accept("contentDelta", chunk))
                    .onCompleteResponse(r -> {
                        log.info("ActivityAgent streaming LLM+tools completed tookMs={}", System.currentTimeMillis() - t0);
                        done.countDown();
                    })
                    .onError(e -> {
                        log.error("ActivityAgent streaming error afterMs={}", System.currentTimeMillis() - t0, e);
                        eventSink.accept("contentDelta", "抱歉，我现在有点忙，请稍后再试。（流式调用异常）");
                        done.countDown();
                    })
                    .start();
            if (!done.await(STREAM_WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                log.warn("ActivityAgent streaming wait timed out afterMs={}", System.currentTimeMillis() - t0);
            }
        } catch (InputGuardrailException e) {
            String msg = extractGuardrailBlockMessage(e.getMessage());
            log.warn("ActivityAgent streaming input guardrail blocked: {}", msg);
            eventSink.accept("contentBanned", msg);
        } catch (Exception e) {
            log.error("Error in ActivityAgent streaming", e);
            eventSink.accept("contentDelta", "抱歉，我现在有点忙，请稍后再试。（LLM 调用失败）");
        }
    }

    /** 从 LangChain4j InputGuardrailException 中取出给用户看的拦截话术（框架可能包成 "The guardrail ... failed with this message: xxx"） */
    private static String extractGuardrailBlockMessage(String exceptionMessage) {
        if (exceptionMessage == null || exceptionMessage.isEmpty()) {
            return "您的问题涉及敏感或违规内容，请换一种方式提问。";
        }
        String prefix = "this message: ";
        int idx = exceptionMessage.lastIndexOf(prefix);
        if (idx >= 0 && idx + prefix.length() < exceptionMessage.length()) {
            return exceptionMessage.substring(idx + prefix.length()).trim();
        }
        return exceptionMessage;
    }

    private record NamePhone(String name, String phone) {}

    /**
     * 用户已给出姓名+电话并表示参加，且会话里已有 searchActivities 写入的 lastActivityIds 时，直接走报名工具，避免模型空转追问。
     * 支持：仅回复「是的」时从上一轮用户话里找回姓名、手机号后再报名。
     */
    private String tryHandleRegisterContinuation(AgentRequest request) {
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        String phone = RegistrationParsers.extractPhone(message);
        String name = RegistrationParsers.extractName(message);
        boolean contactInCurrentTurn = phone != null && name != null;

        if (!contactInCurrentTurn) {
            NamePhone recovered = recoverNamePhoneFromRecentUsers(request);
            if (recovered != null) {
                name = recovered.name();
                phone = recovered.phone();
            }
        }
        if (phone == null || name == null) {
            return null;
        }

        // 姓名电话来自历史时，须近期用户话里出现过报名意向，避免无关场景里一句「是的」误报名
        if (!contactInCurrentTurn && !recentUserMessagesSuggestRegistration(request)) {
            return null;
        }

        boolean shortAffirm = isShortRegistrationAffirmation(message);
        boolean explicitSubmit = RegistrationParsers.looksLikeRegisterSubmit(message);
        // 本轮已写出姓名+电话，且近期对话里有报名/参加语境时，视为提交报名（不必非写「是的」）
        if (!explicitSubmit && !shortAffirm
                && !(contactInCurrentTurn && recentUserMessagesSuggestRegistration(request))) {
            return null;
        }

        List<String> lastIds = memoryService.getWorkingMemory(request.getSessionId(), request.getUserId())
                .map(m -> LastActivityIdsSupport.normalize(m.get("lastActivityIds")))
                .orElse(null);
        if (lastIds == null || lastIds.isEmpty()) {
            return null;
        }
        String idFragment = resolveRegisterActivityIdToken(request, message);
        String activityId;
        if (idFragment != null && !idFragment.isBlank()) {
            activityId = idFragment.trim();
        } else if (lastIds.size() == 1) {
            activityId = "1";
        } else {
            // 多场活动且本句未写清第几个 / ID，交给模型（避免误报）
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("activityId", activityId);
        params.put("name", name);
        params.put("phone", phone);
        params.put("email", null);
        log.info("Deterministic registerActivity: activityId={}, name={}, phone={}, shortAffirm={}", activityId, name, phone, shortAffirm);
        return String.valueOf(toolCenter.executeTool("registerActivity", params, PermissionLevel.L2));
    }

    /** 仅「是的」「好」等短确认，且当前句内没有新的业务信息 */
    private static boolean isShortRegistrationAffirmation(String message) {
        String t = message.trim();
        if (t.length() > 24) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return t.equals("是的") || t.equals("是") || t.equals("好") || t.equals("好的") || t.equals("嗯")
                || t.equals("嗯嗯") || t.equals("确认") || t.equals("可以") || t.equals("继续")
                || t.equals("报名") || lower.equals("ok") || t.equals("行");
    }

    /**
     * 活动序号/ID 常在上一轮用户话里（如「第三个」），姓名电话在下一轮；合并近期用户输入再解析。
     */
    private String resolveRegisterActivityIdToken(AgentRequest request, String message) {
        String direct = RegistrationParsers.extractActivityOrdinalOrNumericToken(message);
        if (direct != null) {
            return direct;
        }
        return memoryService.getSessionMemory(request.getSessionId(), request.getUserId())
                .map(history -> {
                    int take = Math.min(RECENT_HISTORY_TURNS, history.size());
                    StringBuilder sb = new StringBuilder();
                    for (int i = history.size() - take; i < history.size(); i++) {
                        sb.append(' ').append(safe(history.get(i).get("user")));
                    }
                    if (message != null) {
                        sb.append(' ').append(message);
                    }
                    return RegistrationParsers.extractActivityOrdinalOrNumericToken(sb.toString());
                })
                .orElse(null);
    }

    private NamePhone recoverNamePhoneFromRecentUsers(AgentRequest request) {
        return memoryService.getSessionMemory(request.getSessionId(), request.getUserId())
                .map(history -> {
                    int take = Math.min(RECENT_HISTORY_TURNS, history.size());
                    StringBuilder sb = new StringBuilder();
                    for (int i = history.size() - take; i < history.size(); i++) {
                        sb.append(' ').append(safe(history.get(i).get("user")));
                    }
                    String blob = sb.toString();
                    String p = RegistrationParsers.extractPhone(blob);
                    String n = RegistrationParsers.extractName(blob);
                    if (p != null && n != null) {
                        return new NamePhone(n, p);
                    }
                    return null;
                })
                .orElse(null);
    }

    private boolean recentUserMessagesSuggestRegistration(AgentRequest request) {
        return memoryService.getSessionMemory(request.getSessionId(), request.getUserId())
                .map(history -> {
                    int take = Math.min(RECENT_HISTORY_TURNS, history.size());
                    StringBuilder sb = new StringBuilder();
                    for (int i = history.size() - take; i < history.size(); i++) {
                        sb.append(safe(history.get(i).get("user")));
                    }
                    String blob = sb.toString();
                    return blob.contains("报名") || blob.contains("参加") || blob.contains("注册")
                            || RegistrationParsers.extractPhone(blob) != null;
                })
                .orElse(false);
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
                .skip(Math.max(0, history.size() - RECENT_HISTORY_TURNS))
                .map(item -> "用户：" + safe(item.get("user")) + "\n助手：" + safe(item.get("assistant")))
                .collect(Collectors.joining("\n"));
    }

    private boolean hasRecentCreateIntent(AgentRequest request) {
        Set<String> createKeywords = Set.of("创建活动", "发起活动", "发布活动", "办活动", "搞活动");
        return memoryService.getSessionMemory(request.getSessionId(), request.getUserId())
                .map(history -> history.stream()
                        .skip(Math.max(0, history.size() - RECENT_HISTORY_TURNS))
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
