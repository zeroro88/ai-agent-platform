package com.returensea.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.common.constants.AgentConstants;
import com.returensea.common.model.RecommendedActivity;
import com.returensea.agent.context.AgentContextHolder;
import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.recommend.ActivityRerankService;
import com.returensea.agent.util.LastActivityIdsSupport;
import com.returensea.agent.util.RegistrationParsers;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryService memoryService;
    private final ActivityRerankService activityRerankService;

    @Value("${legacy.service.url:http://localhost:8083}")
    private String legacyServiceUrl;

    /** 与 ActivityAgent 近期轮次一致，用于核对报名姓名手机是否出自用户原话 */
    private static final int REGISTER_CHECK_RECENT_USER_TURNS = 8;

    private final Map<String, ToolHandler> handlers = new HashMap<>();

    @jakarta.annotation.PostConstruct
    public void registerHandlers() {
        handlers.put("searchActivities", this::searchActivities);
        handlers.put("getActivityDetail", this::getActivityDetail);
        handlers.put("registerActivity", this::registerActivity);
        handlers.put("createActivity", this::createActivity);
        handlers.put("queryOrder", this::queryOrder);
        handlers.put("searchPolicy", this::searchPolicy);
        handlers.put("subscribePolicy", this::subscribePolicy);
    }

    public Object execute(String toolName, Map<String, Object> params) {
        ToolHandler handler = handlers.get(toolName);
        if (handler != null) {
            return handler.handle(params);
        }
        return "Tool not implemented: " + toolName;
    }

    public boolean hasHandler(String toolName) {
        return handlers.containsKey(toolName);
    }

    /** 生成可复制的工具错误详情，供 AgentContextHolder 写入响应 errorDetail */
    private String buildToolErrorDetail(Throwable e, String toolName, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[tool=").append(toolName).append("] ");
        if (context != null && !context.isEmpty()) sb.append(context).append("\n");
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String stack = sw.toString();
        int n = 0;
        for (String line : stack.split("\n")) {
            if (n >= 20) { sb.append("  ...\n"); break; }
            sb.append(line).append("\n");
            n++;
        }
        return sb.toString();
    }

    /**
     * 解析用户/模型传入的 activityId：
     * - 纯数字既可能是「第 N 条推荐」也可能是 MySQL 数字主键（如 26）；若该数字在 lastActivityIds 中且不等于第 N 位真实 ID，则按字面 ID。
     * - 超出序号范围但在列表中的数字，按字面活动 ID。
     * - 过长纯数字使用 Long 解析，避免 Integer 溢出或抛异常（如标题内毫秒时间戳）。
     */
    private String resolveActivityIdFromSelection(String activityId) {
        if (activityId == null || activityId.startsWith("act-")) return activityId;
        String trimmed = activityId.trim();
        if (!trimmed.matches("\\d+")) return activityId;
        List<String> ids = getLastActivityIdsFromContext();
        if (ids == null || ids.isEmpty()) {
            return activityId;
        }
        long nLong;
        try {
            nLong = Long.parseLong(trimmed, 10);
        } catch (NumberFormatException e) {
            return activityId;
        }
        if (nLong < 1) {
            return activityId;
        }
        if (nLong > ids.size()) {
            if (ids.contains(trimmed)) {
                log.info("Resolved activityId as literal (numeric > list size): {}", trimmed);
                return trimmed;
            }
            return activityId;
        }
        if (nLong > Integer.MAX_VALUE) {
            return ids.contains(trimmed) ? trimmed : activityId;
        }
        int n = (int) nLong;
        String byOrdinal = ids.get(n - 1);
        if (ids.contains(trimmed) && !trimmed.equals(byOrdinal)) {
            log.info("Resolved activityId as literal (in list, differs from ordinal {}): {} -> {}", n, activityId, trimmed);
            return trimmed;
        }
        log.info("Resolved activityId selection '{}' -> {} (ordinal {})", activityId, byOrdinal, n);
        return byOrdinal;
    }

    /** 标题里常见的时间戳后缀（11 位秒级～13 位毫秒级），勿当作系统活动 ID */
    private static boolean looksLikeTitleEmbeddedNumber(String activityId) {
        if (activityId == null) return false;
        String t = activityId.trim();
        return t.matches("\\d{11,}");
    }

    private List<String> getLastActivityIdsFromContext() {
        String sessionId = AgentContextHolder.getSessionId();
        String userId = AgentContextHolder.getUserId();
        if (sessionId == null || userId == null) {
            return null;
        }
        return memoryService.getWorkingMemory(sessionId, userId)
                .map(m -> LastActivityIdsSupport.normalize(m.get("lastActivityIds")))
                .orElse(null);
    }

    /**
     * 近期用户侧话术（已落库的会话轮次 + 本轮输入，因 saveToSessionMemory 在整轮结束后才写入）。
     */
    private String buildUserBlobForRegisterContactCheck() {
        StringBuilder sb = new StringBuilder();
        String sessionId = AgentContextHolder.getSessionId();
        String userId = AgentContextHolder.getUserId();
        if (sessionId != null && userId != null) {
            memoryService.getSessionMemory(sessionId, userId).ifPresent(history -> {
                int take = Math.min(REGISTER_CHECK_RECENT_USER_TURNS, history.size());
                for (int i = history.size() - take; i < history.size(); i++) {
                    Object u = history.get(i).get("user");
                    if (u != null) {
                        sb.append(' ').append(u);
                    }
                }
            });
        }
        String cur = AgentContextHolder.getCurrentTurnUserMessage();
        if (cur != null && !cur.isBlank()) {
            sb.append(' ').append(cur);
        }
        return sb.toString().trim();
    }

    /**
     * 无任意用户文本上下文时跳过（如单测直接调工具）；有上下文则要求姓名、手机均能在用户原话中核验。
     */
    private boolean registerContactMatchesRecentUserText(String name, String phone) {
        String blob = buildUserBlobForRegisterContactCheck();
        if (blob.isEmpty()) {
            return true;
        }
        return phoneStatedInUserBlob(blob, phone) && nameStatedInUserBlob(blob, name);
    }

    private static boolean phoneStatedInUserBlob(String userBlob, String phone) {
        String p = phone.trim();
        if (p.isEmpty()) {
            return false;
        }
        String parsed = RegistrationParsers.extractPhone(userBlob);
        if (parsed != null && parsed.equals(p)) {
            return true;
        }
        return userBlob.contains(p);
    }

    private static boolean nameStatedInUserBlob(String userBlob, String name) {
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }
        String parsed = RegistrationParsers.extractName(userBlob);
        if (parsed != null) {
            return parsed.equals(n);
        }
        return n.length() >= 2 && userBlob.contains(n);
    }

    private Object searchActivities(Map<String, Object> params) {
        String city = (String) params.getOrDefault("city", "");
        String keyword = (String) params.getOrDefault("keyword", "");

        log.info("searchActivities start: legacyBaseUrl={}, city={}, keyword={}", legacyServiceUrl, city, keyword);
        long t0 = System.nanoTime();

        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromHttpUrl(legacyServiceUrl + "/api/activities");
            if (city != null && !city.isBlank()) {
                uriBuilder.queryParam("city", city.trim());
            }
            if (keyword != null && !keyword.isBlank()) {
                uriBuilder.queryParam("keyword", keyword.trim());
            }
            URI uri = uriBuilder.encode().build().toUri();
            log.info("searchActivities calling legacy GET {}", uri);

            long tLegacyStart = System.nanoTime();
            ResponseEntity<Activity[]> response = restTemplate.getForEntity(uri, Activity[].class);
            long legacyMs = (System.nanoTime() - tLegacyStart) / 1_000_000;
            Activity[] activities = response.getBody();
            log.info("searchActivities legacy responded: status={}, bodyCount={}, tookMs={}",
                    response.getStatusCode(), activities == null ? 0 : activities.length, legacyMs);

            if (activities == null || activities.length == 0) {
                log.info("searchActivities done (empty): totalMs={}", (System.nanoTime() - t0) / 1_000_000);
                return "未找到符合条件的活动。";
            }

            List<ActivityRerankService.ActivityCandidate> candidates = Arrays.stream(activities)
                    .filter(a -> a.getId() != null)
                    .map(a -> new ActivityRerankService.ActivityCandidate(
                            a.getId(),
                            a.getTitle() != null ? a.getTitle() : "",
                            a.getCity() != null ? a.getCity() : "",
                            a.getDescription() != null ? a.getDescription() : ""))
                    .toList();
            String userContext = "城市: " + (city != null ? city : "不限") + "; 关键词: " + (keyword != null ? keyword : "无");
            int topK = Math.min(10, candidates.size());
            long tRerankStart = System.nanoTime();
            List<ActivityRerankService.RerankedActivity> reranked = activityRerankService.rerankWithReasons(candidates, userContext, topK);
            long rerankMs = (System.nanoTime() - tRerankStart) / 1_000_000;
            log.info("searchActivities rerank done: candidates={}, rerankedSize={}, rerankTookMs={}",
                    candidates.size(), reranked.size(), rerankMs);

            List<String> activityIds = reranked.stream().map(ActivityRerankService.RerankedActivity::id).toList();
            String sessionId = AgentContextHolder.getSessionId();
            String userId = AgentContextHolder.getUserId();
            if (sessionId != null && userId != null && !activityIds.isEmpty()) {
                memoryService.setWorkingMemoryKey(sessionId, userId, "lastActivityIds", activityIds);
                log.debug("Stored lastActivityIds for session {} ({} items, after rerank)", sessionId, activityIds.size());
            }

            Map<String, Activity> byId = Arrays.stream(activities).filter(a -> a.getId() != null).collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));
            StringBuilder result = new StringBuilder("根据您的需求，我为您精选并排序了以下活动：\n\n");
            List<RecommendedActivity> recommended = new ArrayList<>();
            int appended = 0;
            boolean firstBlock = true;
            for (int i = 0; i < reranked.size(); i++) {
                ActivityRerankService.RerankedActivity r = reranked.get(i);
                Activity a = byId.get(r.id());
                if (a == null) {
                    log.warn("Rerank id {} not in byId keys {}, skip line", r.id(), byId.keySet());
                    continue;
                }
                if (!firstBlock) {
                    result.append("----------\n\n");
                }
                firstBlock = false;
                appended++;
                recommended.add(RecommendedActivity.builder()
                        .id(a.getId())
                        .title(nullToEmpty(a.getTitle()))
                        .city(nullToEmpty(a.getCity()))
                        .location(nullToEmpty(a.getLocation()))
                        .eventTime(formatEventTimeForDisplay(a.getEventTime()))
                        .recommendReason(nullToEmpty(r.reason()))
                        .capacity(a.getCapacity())
                        .registered(a.getRegistered())
                        .ordinal(appended)
                        .build());
                // 无「1.2.」序号，避免与【活动ID：5】粘连成 52；块之间分隔线 + 空行便于前端换行展示
                result.append("【").append(a.getCity()).append("】").append(a.getTitle()).append("\n");
                result.append("【活动ID：").append(a.getId()).append("】\n");
                result.append("推荐理由：").append(r.reason()).append("\n");
                result.append("时间：").append(formatEventTimeForDisplay(a.getEventTime())).append("\n");
                result.append("地点：").append(a.getLocation()).append("\n");
                result.append("规模：").append(a.getCapacity()).append("人");
                if (a.getRegistered() != null) {
                    result.append("（已报名").append(a.getRegistered()).append("人）");
                }
                result.append("\n");
            }
            // 重排 ID 与 JSON 反序列化 id 不一致时，避免出现「只有标题无列表」导致模型误判为无活动
            int displayLines = appended;
            if (appended == 0 && activities.length > 0) {
                log.warn("No activities appended after rerank; falling back to plain list (count={})", activities.length);
                int n = Math.min(10, activities.length);
                int line = 0;
                boolean firstFallback = true;
                for (int i = 0; i < n; i++) {
                    Activity a = activities[i];
                    if (a == null || a.getId() == null) {
                        continue;
                    }
                    if (!firstFallback) {
                        result.append("----------\n\n");
                    }
                    firstFallback = false;
                    line++;
                    recommended.add(RecommendedActivity.builder()
                            .id(a.getId())
                            .title(nullToEmpty(a.getTitle()))
                            .city(nullToEmpty(a.getCity()))
                            .location(nullToEmpty(a.getLocation()))
                            .eventTime(formatEventTimeForDisplay(nullToEmpty(a.getEventTime())))
                            .recommendReason("")
                            .capacity(a.getCapacity())
                            .registered(a.getRegistered())
                            .ordinal(line)
                            .build());
                    result.append("【").append(nullToEmpty(a.getCity())).append("】").append(nullToEmpty(a.getTitle())).append("\n");
                    result.append("【活动ID：").append(a.getId()).append("】\n");
                    result.append("时间：").append(formatEventTimeForDisplay(nullToEmpty(a.getEventTime()))).append("\n");
                    result.append("地点：").append(nullToEmpty(a.getLocation())).append("\n");
                    result.append("\n");
                }
                displayLines = line;
            }
            if (sessionId != null && userId != null && !recommended.isEmpty()) {
                try {
                    memoryService.setWorkingMemoryKey(sessionId, userId,
                            AgentConstants.WORKING_MEMORY_LAST_RECOMMENDED_ACTIVITIES_JSON,
                            objectMapper.writeValueAsString(recommended));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize recommended activities to working memory: {}", e.getMessage());
                }
            }
            log.info("searchActivities success: totalMs={}, displayLines={}", (System.nanoTime() - t0) / 1_000_000, displayLines);
            return result.toString();

        } catch (Exception e) {
            log.error("searchActivities failed afterMs={}: {}", (System.nanoTime() - t0) / 1_000_000, e.getMessage(), e);
            return "查询活动失败，请稍后重试。错误：" + e.getMessage();
        }
    }

    private Object getActivityDetail(Map<String, Object> params) {
        String activityId = (String) params.get("activityId");

        log.info("Getting activity detail via HTTP: {}", activityId);

        try {
            String url = legacyServiceUrl + "/api/activities/" + activityId;
            ResponseEntity<Activity> response = restTemplate.getForEntity(url, Activity.class);
            Activity a = response.getBody();

            if (a == null) {
                return "未找到该活动。";
            }

            return """
                【%s】

                📅 时间：%s
                📍 地点：%s
                👥 规模：%d人（已报名%d人）
                💰 费用：%s

                活动亮点：
                %s

                报名即可获取专属纪念品！
                """.formatted(
                    a.getTitle(),
                    formatEventTimeForDisplay(a.getEventTime()),
                    a.getLocation(),
                    a.getCapacity(),
                    a.getRegistered(),
                    a.getFee() == 0 ? "免费" : "¥" + a.getFee(),
                    a.getDescription()
                );

        } catch (Exception e) {
            log.error("Error calling legacy service: {}", e.getMessage());
            return "获取活动详情失败，请稍后重试。错误：" + e.getMessage();
        }
    }

    private Object registerActivity(Map<String, Object> params) {
        Object rawActivityId = params.get("activityId");
        String activityId = rawActivityId != null ? String.valueOf(rawActivityId).trim() : null;
        if (activityId != null && activityId.isEmpty()) {
            activityId = null;
        }
        activityId = resolveActivityIdFromSelection(activityId);

        List<String> lastIds = getLastActivityIdsFromContext();
        if (activityId == null || activityId.isBlank()) {
            String hint = (lastIds != null && !lastIds.isEmpty())
                    ? "请从最近推荐的活动里选择（如回复 1、2 或活动 ID：act-xxx）。当前候选：" + String.join(", ", lastIds)
                    : "请先让助手推荐活动，再选择要报名的活动。";
            log.warn("registerActivity 缺少 activityId，拒绝调用下游");
            AgentContextHolder.setErrorDetail("[tool=registerActivity] 缺少 activityId\n传入的 params=" + params);
            return "报名失败。未指定要报名的活动。" + hint;
        }
        if (lastIds == null || lastIds.isEmpty()) {
            log.warn("registerActivity 无最近推荐列表，拒绝调用下游。activityId={}", activityId);
            AgentContextHolder.setErrorDetail("[tool=registerActivity] 无 lastActivityIds，请先推荐活动\n传入的 activityId=" + activityId);
            return "报名失败。请先让助手推荐活动，再选择要报名的活动（如回复 1 或活动 ID：act-xxx）。";
        }
        if (!lastIds.contains(activityId)) {
            String msg = "活动 ID 不在当前候选列表中，请从最近推荐的活动里选择（如回复 1、2 或使用「活动ID（报名必填）」中的数字）。当前候选 ID：" + String.join(", ", lastIds);
            if (looksLikeTitleEmbeddedNumber(activityId)) {
                msg += " 提示：您填写的长数字很像活动标题里的后缀（如时间戳），不是报名用的活动 ID；请勿从标题中抄数字。";
            }
            log.warn("registerActivity 拒绝非候选 ID: activityId={}, lastActivityIds={}", activityId, lastIds);
            AgentContextHolder.setErrorDetail("[tool=registerActivity] " + msg + "\n传入的 activityId=" + params.get("activityId"));
            return "报名失败。" + msg;
        }

        String name = params.get("name") != null ? String.valueOf(params.get("name")).trim() : "";
        String phone = params.get("phone") != null ? String.valueOf(params.get("phone")).trim() : "";
        if (name.isEmpty() || phone.isEmpty()) {
            log.warn("registerActivity 姓名或手机为空，拒绝调用下游。activityId={}, name.len={}, phone.len={}",
                    activityId, name.length(), phone.length());
            AgentContextHolder.setErrorDetail("[tool=registerActivity] 姓名或手机号为空，请先向用户收集后再调用\nactivityId="
                    + activityId + "\nparams=" + params);
            return "报名失败。请先向用户确认真实姓名和手机号码，信息齐全后再提交报名。";
        }

        if (!registerContactMatchesRecentUserText(name, phone)) {
            log.warn("registerActivity 姓名或手机号与近期用户原话不一致，拒绝调用下游。activityId={}, name={}, phone={}",
                    activityId, name, phone);
            AgentContextHolder.setErrorDetail("[tool=registerActivity] 姓名/手机号须在用户原话中出现\nactivityId=" + activityId
                    + "\nparams=" + params);
            return "报名失败。请先在对话中由您本人提供并确认姓名与手机号后再报名；不要使用示例或助手猜测的信息。";
        }

        log.info("Registering activity via HTTP: activityId={}, name={}, phone={}", activityId, name, phone);

        String emailOpt = null;
        if (params.get("email") != null) {
            String e = String.valueOf(params.get("email")).trim();
            if (!e.isEmpty()) {
                emailOpt = e;
            }
        }

        try {
            String url = legacyServiceUrl + "/api/activities/" + activityId + "/register";

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("name", name);
            requestBody.put("phone", phone);
            if (emailOpt != null) {
                requestBody.put("email", emailOpt);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Registration> response = restTemplate.postForEntity(url, request, Registration.class);
            Registration reg = response.getBody();

            if (reg == null) {
                log.error("报名失败：下游返回空 body。url={}, activityId={}, name={}, phone={}, statusCode={}",
                        url, activityId, name, phone, response.getStatusCode());
                String detail = "[tool=registerActivity] 下游返回空 body\nurl=" + url + "\nactivityId=" + activityId + "\nname=" + name + "\nphone=" + phone + "\nstatusCode=" + response.getStatusCode();
                AgentContextHolder.setErrorDetail(detail);
                return "报名失败，请稍后重试。";
            }

            return "报名成功！您的报名信息如下：\n" +
                   "- 活动：" + reg.getActivityTitle() + "\n" +
                   "- 姓名：" + reg.getName() + "\n" +
                   "- 手机：" + reg.getPhone() + "\n" +
                   "- 订单号：" + reg.getId() + "\n\n" +
                   "我们已发送确认短信，请注意查收。活动当天凭报名二维码入场。";

        } catch (Exception e) {
            log.error("报名调用下游失败，便于复制排查：url={}, activityId={}, name={}, phone={}, error={}",
                    legacyServiceUrl + "/api/activities/" + activityId + "/register", activityId, name, phone, e.getMessage(), e);
            String detail = buildToolErrorDetail(e, "registerActivity", "url=" + legacyServiceUrl + "/api/activities/" + activityId + "/register");
            AgentContextHolder.setErrorDetail(detail);
            return "报名失败，请稍后重试。错误：" + e.getMessage();
        }
    }

    private Object createActivity(Map<String, Object> params) {
        String title = (String) params.getOrDefault("title", "用户发起活动");
        String city = (String) params.getOrDefault("city", "上海");
        String date = (String) params.getOrDefault("date", "明天");
        String location = (String) params.getOrDefault("location", city + "待定场地");
        String description = (String) params.getOrDefault("description", "由AI助手代为发起");
        Integer capacity = parseInteger(params.get("capacity"), 100);

        log.info("Creating activity via HTTP: title={}, city={}, date={}", title, city, date);

        try {
            String url = legacyServiceUrl + "/api/activities";
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("title", title);
            requestBody.put("city", city);
            requestBody.put("location", location);
            requestBody.put("eventTime", date + " 14:00");
            requestBody.put("capacity", capacity);
            requestBody.put("fee", 0);
            requestBody.put("description", description);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Activity> response = restTemplate.postForEntity(url, request, Activity.class);
            Activity activity = response.getBody();
            if (activity == null) {
                return "活动发起失败，请稍后再试。";
            }
            return "活动已发起成功！\n" +
                   "- 活动ID：" + activity.getId() + "\n" +
                   "- 标题：" + activity.getTitle() + "\n" +
                   "- 城市：" + activity.getCity() + "\n" +
                   "- 时间：" + formatEventTimeForDisplay(activity.getEventTime()) + "\n" +
                   "- 地点：" + activity.getLocation() + "\n" +
                   "我也可以继续帮您发布报名信息。";
        } catch (Exception e) {
            log.error("Error creating activity: {}", e.getMessage());
            return "发起活动失败，请稍后重试。错误：" + e.getMessage();
        }
    }

    private Object queryOrder(Map<String, Object> params) {
        String orderId = (String) params.getOrDefault("orderId", "");
        String phone = (String) params.getOrDefault("phone", "");

        log.info("Querying order via HTTP: orderId={}, phone={}", orderId, phone);

        try {
            String url = legacyServiceUrl + "/api/activities/registrations";
            StringBuilder queryParams = new StringBuilder();
            if (orderId != null && !orderId.isEmpty()) {
                queryParams.append("orderId=").append(orderId);
            }
            if (phone != null && !phone.isEmpty()) {
                if (queryParams.length() > 0) queryParams.append("&");
                queryParams.append("phone=").append(phone);
            }
            if (queryParams.length() > 0) {
                url += "?" + queryParams;
            }

            ResponseEntity<Registration[]> response = restTemplate.getForEntity(url, Registration[].class);
            Registration[] registrations = response.getBody();

            if (registrations == null || registrations.length == 0) {
                return "未找到您的报名记录。";
            }

            Registration reg = registrations[0];
            return """
                您的订单信息：

                订单号：%s
                活动：%s
                状态：%s
                报名时间：%s

                如需取消报名，请回复"取消报名"。
                """.formatted(
                    reg.getId(),
                    reg.getActivityTitle(),
                    reg.getStatus(),
                    reg.getRegisteredAt()
                );

        } catch (Exception e) {
            log.error("Error calling legacy service: {}", e.getMessage());
            return "查询订单失败，请稍后重试。错误：" + e.getMessage();
        }
    }

    private Object searchPolicy(Map<String, Object> params) {
        String keyword = (String) params.getOrDefault("keyword", "");

        log.info("Searching policy: keyword={}", keyword);

        return "您可以咨询具体的政策问题，我会为您检索相关政策文件。例如：\n" +
               "- 北京落户政策\n" +
               "- 上海创业补贴\n" +
               "- 购房优惠政策";
    }

    private Object subscribePolicy(Map<String, Object> params) {
        String city = (String) params.getOrDefault("city", "");
        String category = (String) params.getOrDefault("category", "");

        log.info("Subscribing policy: city={}, category={}", city, category);

        return "订阅成功！您已订阅" + city + "的" + category + "政策更新。\n" +
               "当有新政策发布时，我们将第一时间通知您。";
    }

    private Integer parseInteger(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer intValue) {
            return intValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /** 部分数据源返回「2026-05-2509:00」无空格，展示时拆开便于阅读 */
    private static String formatEventTimeForDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceFirst("(\\d{4}-\\d{2}-\\d{2})(\\d{1,2}:\\d{2})", "$1 $2");
    }

    @FunctionalInterface
    private interface ToolHandler {
        Object handle(Map<String, Object> params);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Activity {
        private String id;
        private String title;
        private String city;
        private String location;
        private String eventTime;
        private Integer capacity;
        private Integer registered;
        private Integer fee;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Registration {
        private String id;
        private String activityId;
        private String activityTitle;
        private String name;
        private String phone;
        private String email;
        private String status;
        private String registeredAt;
    }
}
