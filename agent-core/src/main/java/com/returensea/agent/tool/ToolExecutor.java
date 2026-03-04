package com.returensea.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.agent.context.AgentContextHolder;
import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.recommend.ActivityRerankService;
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

    /** 若 activityId 为 "1"/"2" 等序号，从当前会话的 lastActivityIds 解析为真实活动 ID */
    private String resolveActivityIdFromSelection(String activityId) {
        if (activityId == null || activityId.startsWith("act-")) return activityId;
        String trimmed = activityId.trim();
        if (!trimmed.matches("\\d+")) return activityId;
        int index = Integer.parseInt(trimmed, 10);
        if (index < 1) return activityId;
        String sessionId = AgentContextHolder.getSessionId();
        String userId = AgentContextHolder.getUserId();
        if (sessionId == null || userId == null) return activityId;
        @SuppressWarnings("unchecked")
        List<String> ids = memoryService.getWorkingMemory(sessionId, userId)
                .map(m -> m.get("lastActivityIds"))
                .filter(List.class::isInstance)
                .map(list -> (List<String>) list)
                .orElse(null);
        if (ids != null && index <= ids.size()) {
            String resolved = ids.get(index - 1);
            log.info("Resolved activityId selection '{}' -> {}", activityId, resolved);
            return resolved;
        }
        return activityId;
    }

    @SuppressWarnings("unchecked")
    private List<String> getLastActivityIdsFromContext() {
        String sessionId = AgentContextHolder.getSessionId();
        String userId = AgentContextHolder.getUserId();
        if (sessionId == null || userId == null) return null;
        return memoryService.getWorkingMemory(sessionId, userId)
                .map(m -> m.get("lastActivityIds"))
                .filter(List.class::isInstance)
                .map(list -> (List<String>) list)
                .orElse(null);
    }

    private Object searchActivities(Map<String, Object> params) {
        String city = (String) params.getOrDefault("city", "");
        String keyword = (String) params.getOrDefault("keyword", "");

        log.info("Searching activities via HTTP: city={}, keyword={}", city, keyword);

        try {
            String url = legacyServiceUrl + "/api/activities";
            StringBuilder queryParams = new StringBuilder();
            if (city != null && !city.isEmpty()) {
                queryParams.append("city=").append(city);
            }
            if (keyword != null && !keyword.isEmpty()) {
                if (queryParams.length() > 0) queryParams.append("&");
                queryParams.append("keyword=").append(keyword);
            }
            if (queryParams.length() > 0) {
                url += "?" + queryParams;
            }

            ResponseEntity<Activity[]> response = restTemplate.getForEntity(url, Activity[].class);
            Activity[] activities = response.getBody();

            if (activities == null || activities.length == 0) {
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
            List<ActivityRerankService.RerankedActivity> reranked = activityRerankService.rerankWithReasons(candidates, userContext, topK);

            List<String> activityIds = reranked.stream().map(ActivityRerankService.RerankedActivity::id).toList();
            String sessionId = AgentContextHolder.getSessionId();
            String userId = AgentContextHolder.getUserId();
            if (sessionId != null && userId != null && !activityIds.isEmpty()) {
                memoryService.setWorkingMemoryKey(sessionId, userId, "lastActivityIds", activityIds);
                log.debug("Stored lastActivityIds for session {} ({} items, after rerank)", sessionId, activityIds.size());
            }

            Map<String, Activity> byId = Arrays.stream(activities).filter(a -> a.getId() != null).collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));
            StringBuilder result = new StringBuilder("根据您的需求，我为您精选并排序了以下活动：\n\n");
            for (int i = 0; i < reranked.size(); i++) {
                ActivityRerankService.RerankedActivity r = reranked.get(i);
                Activity a = byId.get(r.id());
                if (a == null) continue;
                result.append(i + 1).append(". 【").append(a.getCity()).append("】").append(a.getTitle()).append("\n");
                result.append("   推荐理由：").append(r.reason()).append("\n");
                result.append("   时间：").append(a.getEventTime()).append("\n");
                result.append("   地点：").append(a.getLocation()).append("\n");
                result.append("   规模：").append(a.getCapacity()).append("人");
                if (a.getRegistered() != null) {
                    result.append("（已报名").append(a.getRegistered()).append("人）");
                }
                result.append("\n\n");
            }
            return result.toString();

        } catch (Exception e) {
            log.error("Error calling legacy service: {}", e.getMessage());
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
                    a.getEventTime(),
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
        String activityId = (String) params.get("activityId");
        activityId = resolveActivityIdFromSelection(activityId);
        String name = (String) params.get("name");
        String phone = (String) params.get("phone");
        String email = (String) params.get("email");

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
            String msg = "活动 ID 不在当前候选列表中，请从最近推荐的活动里选择（如回复 1、2 或活动 ID：act-xxx）。当前候选 ID：" + String.join(", ", lastIds);
            log.warn("registerActivity 拒绝非候选 ID: activityId={}, lastActivityIds={}", activityId, lastIds);
            AgentContextHolder.setErrorDetail("[tool=registerActivity] " + msg + "\n传入的 activityId=" + params.get("activityId"));
            return "报名失败。" + msg;
        }

        log.info("Registering activity via HTTP: activityId={}, name={}, phone={}", activityId, name, phone);

        try {
            String url = legacyServiceUrl + "/api/activities/" + activityId + "/register";

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("name", name);
            requestBody.put("phone", phone);
            if (email != null) requestBody.put("email", email);

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
                   "- 时间：" + activity.getEventTime() + "\n" +
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
