package com.returensea.gateway.e2e;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h2>HTTP 端到端测试（真实依赖，非进程内 Spring IT）</h2>
 * <p>
 * 本类通过 {@code POST /api/v1/chat} 调用已启动的 <b>ai-gateway</b>，走完整链路：
 * Gateway → agent-core → legacy-dummy / rag-service，并依赖可用的 <b>LLM</b> 配置。
 * </p>
 *
 * <h3>运行前请手动启动</h3>
 * <ol>
 *   <li>中间件（按需）：
 *       {@code docker compose -f docker/docker-compose.yml up -d mysql redis}
 *       （政策 RAG 若用 Milvus：再 {@code up -d milvus} 及依赖）</li>
 *   <li>各服务（建议 {@code middleware} profile，与 README 一致）：
 *       <pre>
 *       mvn spring-boot:run -pl legacy-dummy -Dspring-boot.run.profiles=middleware
 *       mvn spring-boot:run -pl rag-service -Dspring-boot.run.profiles=middleware
 *       mvn spring-boot:run -pl agent-core -Dspring-boot.run.profiles=middleware
 *       mvn spring-boot:run -pl ai-gateway -Dspring-boot.run.profiles=middleware
 *       </pre>
 *   </li>
 *   <li>在 agent-core / gateway 中配置好 LLM（如 {@code ai.agent.llm.*}），否则用例会失败或超时。</li>
 *   <li>调整 {@code agent-core.read-timeout} 后须<strong>重新编译并重启</strong> ai-gateway；未重启时仍可能按旧 30s 出现 {@code flatMap} 超时。</li>
 * </ol>
 *
 * <h3>地址与超时</h3>
 * <ul>
 *   <li>默认网关：{@code http://localhost:8081}</li>
 *   <li>覆盖：环境变量 {@code E2E_GATEWAY_BASE_URL} 或系统属性 {@code e2e.gateway.baseUrl}</li>
 *   <li>单次 HTTP 读超时见 {@link #HTTP_READ_TIMEOUT_SECONDS}（秒，LLM 较慢时可改常量）</li>
 * </ul>
 *
 * <h3>Maven</h3>
 * <pre>
 * # 默认 mvn test 不执行本包（Surefire 排除 glob：任意路径下的 e2e 目录）
 * mvn test -Pe2e -pl ai-gateway
 * </pre>
 *
 * <h3>Redis 与记忆层</h3>
 * <p>
 * {@code middleware} 下 agent-core 的 {@code MemoryServiceImpl} 在注入 {@code StringRedisTemplate} 时会将会话、工作记忆、槽位等写入 Redis；
 * 未连接 Redis 时同一实现退化为进程内 Map。仅「推荐活动」后校验 {@code agent:working} 与 {@code lastActivityIds} 见 {@code journey_recommendShanghai_workingMemory_hasLastActivityIdsInRedis}；
 * 链路「报名槽位写入 Redis」见 {@code journey_register_partialSlots_persistedInRedis}（需本机可连 Redis，与 agent-core 一致）；
 * 单元级校验另见 agent-core 的 {@code RedisMemoryIntegrationTest}。
 * </p>
 *
 * <p>覆盖「推荐后必须列出活动详情」：首轮带城市（如「推荐一些上海的活动」）再答「都可以」，助手不得只写概括却不展示标题/时间/地点/活动ID。</p>
 *
 * <p>覆盖意图路由 <b>快轨 / 慢轨 / 混合</b>（{@code RouteType}）：慢轨为网关固定话术且不调用 agent-core；快轨与混合均走 agent，仅通过「非慢轨话术 + agentType 非 SYSTEM」做黑盒区分。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CriticalUserJourneysHttpE2EIT {

    private static final int HTTP_READ_TIMEOUT_SECONDS = 180;

    /** 与网关 {@code AgentGatewayService} 慢轨固定话术一致（用于断言慢轨） */
    private static final String SLOW_TRACK_CONTENT_SNIPPET = "此操作需要通过传统方式处理";

    /** 与 agent-core {@code application-middleware.yml} 默认一致；可用 {@code E2E_REDIS_HOST} / {@code E2E_REDIS_PORT} 覆盖。 */
    private static final String REDIS_HOST = firstNonBlank(
            System.getenv("E2E_REDIS_HOST"),
            System.getProperty("e2e.redis.host"),
            "localhost");
    private static final int REDIS_PORT = parsePort(
            firstNonBlank(System.getenv("E2E_REDIS_PORT"), System.getProperty("e2e.redis.port"), "6379"));

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpClient HTTP_CLIENT;
    private static String gatewayBaseUrl;

    @BeforeAll
    static void initClientAndBaseUrl() {
        gatewayBaseUrl = System.getProperty("e2e.gateway.baseUrl");
        if (gatewayBaseUrl == null || gatewayBaseUrl.isBlank()) {
            String env = System.getenv("E2E_GATEWAY_BASE_URL");
            gatewayBaseUrl = (env != null && !env.isBlank()) ? env.trim() : "http://localhost:8081";
        }
        gatewayBaseUrl = gatewayBaseUrl.replaceAll("/$", "");
        HTTP_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @BeforeEach
    void assumeGatewayReachable() {
        assumeGatewayHealthy();
    }

    private static void assumeGatewayHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayBaseUrl + "/api/v1/chat/health"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assumptions.assumeTrue(resp.statusCode() == 200,
                    "网关不可达或健康检查非 200（请先启动 ai-gateway:8081）。baseUrl=" + gatewayBaseUrl + " status=" + resp.statusCode());
        } catch (Exception e) {
            Assumptions.abort("跳过 e2e：无法连接网关 " + gatewayBaseUrl + " — " + e.getMessage());
        }
    }

    @Test
    @Order(1)
    @DisplayName("链路1：发起活动 → 补充要素 → 确认发起 → 创建成功语义")
    void journey_createActivity_confirmAndSucceed() throws Exception {
        String session = "e2e-create-" + UUID.randomUUID();
        String user = "e2e-user";

        JsonNode r1 = postChat(session, user, "我要发起活动");
        assertSoftlyNoHardError(r1);
        String t1 = displayText(r1);
        assertThat(t1).isNotBlank();

        JsonNode r2 = postChat(session, user, "上海，明天，E2E自动化咖啡交流会");
        assertSoftlyNoHardError(r2);
        String t2 = displayText(r2);
        assertThat(t2)
                .as("应进入草稿确认或继续收集信息")
                .containsAnyOf("草稿", "确认", "发起", "标题", "城市", "时间");

        JsonNode r3 = postChat(session, user, "确认发起");
        assertSoftlyNoHardError(r3);
        String t3 = displayText(r3);
        assertThat(t3)
                .as("创建成功或工具返回应包含成功语义")
                .satisfiesAnyOf(
                        text -> assertThat(text).containsIgnoringCase("成功"),
                        text -> assertThat(text).contains("活动ID"),
                        text -> assertThat(text).contains("已发起"),
                        text -> assertThat(text).contains("发起成功"));
    }

    @Test
    @Order(2)
    @DisplayName("链路2：推荐活动 → 同会话报名（姓名+手机）→ 成功语义")
    void journey_recommendShanghai_thenRegister() throws Exception {
        String session = "e2e-reg-" + UUID.randomUUID();
        String user = "e2e-user";

        JsonNode r1 = postChat(session, user, "推荐上海的活动");
        assertSoftlyNoHardError(r1);
        String t1 = displayText(r1);
        assertThat(t1).isNotBlank();

        JsonNode r2 = postChat(session, user, "我要报名刚才推荐里第一个活动，姓名赵六，手机 13900009988");
        assertSoftlyNoHardError(r2);
        String t2 = displayText(r2);
        assertThat(t2)
                .as("报名成功或正在处理，不应出现明确拒绝报名的固定失败话术")
                .doesNotContain("无最近推荐列表")
                .doesNotContain("请先让助手推荐活动");
        assertThat(t2)
                .satisfiesAnyOf(
                        text -> assertThat(text).containsIgnoringCase("成功"),
                        text -> assertThat(text).contains("报名"),
                        text -> assertThat(text).contains("已"),
                        text -> assertThat(text).contains("完成"));
    }

    @Test
    @Order(3)
    @DisplayName("链路2a：推荐上海活动 → Redis 工作记忆含 lastActivityIds")
    void journey_recommendShanghai_workingMemory_hasLastActivityIdsInRedis() throws Exception {
        assumeRedisReachableForE2e();

        String session = "e2e-redis-working-" + UUID.randomUUID();
        String user = "e2e-user";
        String workingKey = "agent:working:" + session + ":" + user;

        JsonNode r1 = postChat(session, user, "推荐上海的活动");
        assertSoftlyNoHardError(r1);
        assertThat(displayText(r1)).isNotBlank();

        String raw = readRawFromRedis(workingKey);
        assertThat(raw)
                .as("搜活动后应写入工作记忆（键=%s）", workingKey)
                .isNotBlank();

        JsonNode wm = MAPPER.readTree(raw);
        JsonNode lastIds = wm.get("lastActivityIds");
        assertThat(lastIds)
                .as("工作记忆应含 lastActivityIds")
                .isNotNull();
        assertThat(lastIds.isArray())
                .as("lastActivityIds 应为 JSON 数组")
                .isTrue();
        assertThat(lastIds.size())
                .as("searchActivities 成功后应至少有一条推荐活动 ID")
                .isGreaterThan(0);

        assertThat(raw)
                .as("updateWorkingMemory 应追加用户原文")
                .contains("推荐上海");
    }

    @Test
    @Order(4)
    @DisplayName("链路2b：推荐 → 分轮报名 → Redis 槽位含活动/手机/姓名；会话键含原文")
    void journey_register_partialSlots_persistedInRedis() throws Exception {
        assumeRedisReachableForE2e();

        String session = "e2e-redis-reg-" + UUID.randomUUID();
        String user = "e2e-user";
        String slotsKey = "agent:slots:" + session + ":" + user + ":ACTIVITY_REGISTER";
        String sessionKey = "agent:session:" + session + ":" + user;

        JsonNode r1 = postChat(session, user, "推荐上海的活动");
        assertSoftlyNoHardError(r1);
        assertThat(displayText(r1)).isNotBlank();

        JsonNode r2 = postChat(session, user, "我要报名刚才推荐里第一个活动");
        assertSoftlyNoHardError(r2);
        assertThat(displayText(r2)).isNotBlank();

        Map<String, Object> afterPick = readSlotStateFromRedis(slotsKey);
        assertThat(afterPick)
                .as("middleware 下未完成报名时应把已填槽位写入 Redis（键=%s）", slotsKey)
                .isNotEmpty();
        assertThat(stringSlot(afterPick, "activityId"))
                .as("首轮报名应已解析活动 ID 并写入 Redis")
                .isNotBlank();

        // 网关可能把纯手机号判成 CHITCHAT；agent-core Orchestrator 应在已有 activityId 时合并槽位延续（见 coerceActivityRegisterIntentIfNeeded）。
        JsonNode r3 = postChat(session, user, "手机13900009988");
        assertSoftlyNoHardError(r3);
        assertThat(displayText(r3)).isNotBlank();

        Map<String, Object> afterPhone = readSlotStateFromRedis(slotsKey);
        assertThat(stringSlot(afterPhone, "phone"))
                .as("单独提供手机号后 Redis 槽位应含 phone")
                .contains("13900009988");
        assertThat(stringSlot(afterPhone, "activityId"))
                .as("槽位状态应保留 activityId")
                .isNotBlank();

        JsonNode r4 = postChat(session, user, "姓名赵六");
        assertSoftlyNoHardError(r4);
        String t4 = displayText(r4);
        assertThat(t4)
                .as("补全姓名后应完成报名语义")
                .doesNotContain("无最近推荐列表")
                .doesNotContain("请先让助手推荐活动");
        assertThat(t4)
                .satisfiesAnyOf(
                        text -> assertThat(text).containsIgnoringCase("成功"),
                        text -> assertThat(text).contains("报名"),
                        text -> assertThat(text).contains("已"),
                        text -> assertThat(text).contains("完成"));

        Map<String, Object> afterDone = readSlotStateFromRedis(slotsKey);
        assertThat(afterDone)
                .as("槽位已满并执行报名后 Orchestrator 会 clearSlotState，Redis 中应为空 map")
                .isEmpty();

        String sessionBlob = readRawFromRedis(sessionKey);
        assertThat(sessionBlob)
                .as("每轮对话会写入 agent:session（含用户原文），应能检索到姓名与手机号")
                .contains("赵六")
                .contains("13900009988");
    }

    @Test
    @Order(5)
    @DisplayName("链路3：推荐（含城市）→「都可以」→ 回复须含具体活动信息（非只概括场数）")
    void journey_recommendThenVaguePreference_mustShowActivityDetails() throws Exception {
        String session = "e2e-rec-detail-" + UUID.randomUUID();
        String user = "e2e-user";

        // 首轮带上城市，避免模型第二轮仍追问城市导致短澄清（仅「推荐一些活动」+「都可以」对 LLM 不稳定）
        JsonNode r1 = postChat(session, user, "推荐一些上海的活动");
        assertSoftlyNoHardError(r1);
        String t1 = displayText(r1);
        assertThat(t1).isNotBlank();

        JsonNode r2 = postChat(session, user, "都可以");
        assertSoftlyNoHardError(r2);
        String t2 = displayText(r2);
        assertThat(t2)
                .as("第二轮在已限定城市后应有实质推荐内容，不能只有空话")
                .hasSizeGreaterThan(80);
        // 工具侧为「【活动ID：…】」；模型若改写须至少保留时间+地点等结构化信息
        boolean hasActivityIdLabel = t2.contains("【活动ID：")
                || t2.contains("活动ID")
                || t2.toLowerCase().contains("活动 id");
        boolean hasTimeAndPlace = t2.contains("时间") && t2.contains("地点");
        assertThat(hasActivityIdLabel || hasTimeAndPlace)
                .as("须含「【活动ID：」/「活动ID」或同时含「时间」「地点」，避免只写推荐了几场却不展示列表")
                .isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("链路4：政策咨询 → 返回答案（非知识库不可用）")
    void journey_policyConsultation_returnsAnswer() throws Exception {
        String session = "e2e-policy-" + UUID.randomUUID();
        String user = "e2e-user";

        JsonNode r1 = postChat(session, user, "上海海归落户政策有哪些补贴");
        assertSoftlyNoHardError(r1);
        String t1 = displayText(r1);
        assertThat(t1)
                .as("政策应答应有实质内容")
                .isNotBlank()
                .doesNotContain("知识库服务暂时不可用")
                .hasSizeGreaterThan(8);
        assertThat(t1)
                .satisfiesAnyOf(
                        text -> assertThat(text).containsIgnoringCase("落户"),
                        text -> assertThat(text).contains("政策"),
                        text -> assertThat(text).contains("补贴"),
                        text -> assertThat(text).contains("海归"));
    }

    @Test
    @Order(7)
    @DisplayName("路由-慢轨：L3 敏感操作（支付）→ 网关固定话术、不调 agent-core")
    void journey_route_slowTrack_l3_payment_fixedSystemResponse() throws Exception {
        String session = "e2e-route-slow-" + UUID.randomUUID();
        String user = "e2e-user";

        JsonNode r1 = postChat(session, user, "我要支付订单费用");
        assertSoftlyNoHardError(r1);
        assertThat(agentType(r1))
                .as("慢轨应由网关直接返回 SYSTEM")
                .isEqualTo("SYSTEM");
        assertThat(displayText(r1))
                .as("慢轨固定提示")
                .contains(SLOW_TRACK_CONTENT_SNIPPET);
    }

    @Test
    @Order(8)
    @DisplayName("路由-快轨：闲聊 L0 → 走 agent，非慢轨话术")
    void journey_route_fastTrack_chitchat_notSlowTrack() throws Exception {
        String session = "e2e-route-fast-" + UUID.randomUUID();
        String user = "e2e-user";

        JsonNode r1 = postChat(session, user, "你好呀请问在吗");
        assertSoftlyNoHardError(r1);
        assertThat(displayText(r1))
                .as("快轨不得返回慢轨固定话术")
                .doesNotContain(SLOW_TRACK_CONTENT_SNIPPET);
        assertThat(agentType(r1))
                .as("应答来自领域 Agent，非网关 SYSTEM 慢轨")
                .isNotEqualTo("SYSTEM")
                .isNotBlank();
    }

    @Test
    @Order(9)
    @DisplayName("路由-混合：L2（报名）→ 仍走 agent，非慢轨话术")
    void journey_route_hybrid_l2_register_notSlowTrack() throws Exception {
        String session = "e2e-route-hybrid-" + UUID.randomUUID();
        String user = "e2e-user";

        JsonNode r1 = postChat(session, user, "我要报名参加人工智能讲座");
        assertSoftlyNoHardError(r1);
        assertThat(displayText(r1))
                .as("混合轨仍调用 agent-core，不应命中慢轨")
                .doesNotContain(SLOW_TRACK_CONTENT_SNIPPET);
        assertThat(agentType(r1))
                .as("混合轨 agentType 应为业务 Agent")
                .isNotEqualTo("SYSTEM")
                .isNotBlank();
    }

    private static JsonNode postChat(String sessionId, String userId, String message) throws Exception {
        String body = MAPPER.createObjectNode()
                .put("message", message)
                .put("sessionId", sessionId)
                .put("userId", userId)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBaseUrl + "/api/v1/chat"))
                .timeout(Duration.ofSeconds(HTTP_READ_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode())
                .as("HTTP 状态应为 200")
                .isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private static String agentType(JsonNode chatResponseRoot) {
        if (chatResponseRoot == null || chatResponseRoot.isNull()) {
            return "";
        }
        JsonNode n = chatResponseRoot.get("agentType");
        return n == null || n.isNull() ? "" : n.asText("");
    }

    /** agent-core 常把 content 做成 JSON：{"type":"message","text":"..."} */
    private static String displayText(JsonNode chatResponseRoot) {
        if (chatResponseRoot == null || chatResponseRoot.isNull()) {
            return "";
        }
        JsonNode contentNode = chatResponseRoot.get("content");
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            String raw = contentNode.asText();
            if (raw != null && raw.trim().startsWith("{")) {
                try {
                    JsonNode inner = MAPPER.readTree(raw);
                    JsonNode text = inner.get("text");
                    if (text != null && !text.isNull()) {
                        return text.asText("");
                    }
                } catch (Exception ignored) {
                    return raw;
                }
            }
            return raw;
        }
        return contentNode.toString();
    }

    private static void assertSoftlyNoHardError(JsonNode root) {
        JsonNode err = root.get("error");
        if (err != null && !err.isNull() && !err.asText("").isBlank()) {
            assertThat(err.asText())
                    .as("ChatResponse.error 应为空，实际=" + err.asText())
                    .isBlank();
        }
    }

    private static void assumeRedisReachableForE2e() {
        try (RedisClient client = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
             StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().ping();
        } catch (Exception e) {
            Assumptions.abort("跳过 Redis 槽位断言：无法连接 Redis " + REDIS_HOST + ":" + REDIS_PORT
                    + "（请 docker compose up -d redis，且 agent-core 使用 middleware）。原因: " + e.getMessage());
        }
    }

    private static Map<String, Object> readSlotStateFromRedis(String key) throws Exception {
        String raw = readRawFromRedis(key);
        if (raw.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(raw, new TypeReference<>() {});
    }

    private static String readRawFromRedis(String key) {
        try (RedisClient client = RedisClient.create("redis://" + REDIS_HOST + ":" + REDIS_PORT);
             StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> sync = conn.sync();
            String raw = sync.get(key);
            return raw != null ? raw : "";
        }
    }

    private static String stringSlot(Map<String, Object> slots, String field) {
        if (slots == null) {
            return "";
        }
        Object v = slots.get(field);
        if (v == null) {
            return "";
        }
        return v.toString().trim();
    }

    private static String firstNonBlank(String... parts) {
        if (parts == null) {
            return "";
        }
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                return p.trim();
            }
        }
        return "";
    }

    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 6379;
        }
    }
}
