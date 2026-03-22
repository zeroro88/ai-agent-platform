package com.returensea.gateway.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *       mvn spring-boot:run -pl legacy-dummy -- -Dspring.profiles.active=middleware
 *       mvn spring-boot:run -pl rag-service -- -Dspring.profiles.active=middleware
 *       mvn spring-boot:run -pl agent-core -- -Dspring.profiles.active=middleware
 *       mvn spring-boot:run -pl ai-gateway -- -Dspring.profiles.active=middleware
 *       </pre>
 *   </li>
 *   <li>在 agent-core / gateway 中配置好 LLM（如 {@code ai.agent.llm.*}），否则用例会失败或超时。</li>
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
 * <p>覆盖「推荐后必须列出活动详情」：用户先问推荐、再答「都可以」时，助手不得只写「推荐了两场」却不展示标题/时间/地点/活动ID。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CriticalUserJourneysHttpE2EIT {

    private static final int HTTP_READ_TIMEOUT_SECONDS = 180;

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
    @DisplayName("链路3：推荐 →「都可以」→ 回复须含具体活动信息（非只概括场数）")
    void journey_recommendThenVaguePreference_mustShowActivityDetails() throws Exception {
        String session = "e2e-rec-detail-" + UUID.randomUUID();
        String user = "e2e-user";

        JsonNode r1 = postChat(session, user, "推荐一些活动");
        assertSoftlyNoHardError(r1);
        String t1 = displayText(r1);
        assertThat(t1).isNotBlank();

        JsonNode r2 = postChat(session, user, "都可以");
        assertSoftlyNoHardError(r2);
        String t2 = displayText(r2);
        assertThat(t2)
                .as("第二轮仍应有实质推荐内容，不能只有空话")
                .hasSizeGreaterThan(120);
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
    @Order(4)
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
}
