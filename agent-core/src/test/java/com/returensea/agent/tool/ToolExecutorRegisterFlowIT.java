package com.returensea.agent.tool;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.returensea.agent.AgentCoreApplication;
import com.returensea.agent.context.AgentContextHolder;
import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.recommend.ActivityRerankService;
import com.returensea.agent.recommend.ActivityRerankService.ActivityCandidate;
import com.returensea.agent.recommend.ActivityRerankService.RerankedActivity;
import com.returensea.agent.util.LastActivityIdsSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = AgentCoreApplication.class)
class ToolExecutorRegisterFlowIT {

    private static final WireMockServer WM;

    static {
        WM = new WireMockServer(wireMockConfig().dynamicPort());
        WM.start();
        System.setProperty("wiremock.port", String.valueOf(WM.port()));
    }

    @Autowired
    ToolExecutor toolExecutor;

    @Autowired
    MemoryService memoryService;

    @MockBean
    ActivityRerankService activityRerankService;

    @DynamicPropertySource
    static void legacyUrl(DynamicPropertyRegistry r) {
        r.add("legacy.service.url", () -> "http://localhost:" + System.getProperty("wiremock.port"));
    }

    @AfterAll
    static void stopWireMock() {
        WM.stop();
    }

    @BeforeEach
    void setupWireMockStubs() {
        WM.resetAll();
        String actJson = """
                [{"id":"42","title":"中间件测试","city":"上海","location":"浦东","eventTime":"2026-03-22T09:40:24","capacity":100,"registered":0,"fee":0,"description":"d"}]
                """;
        WM.stubFor(get(urlPathEqualTo("/api/activities"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(actJson)));
        WM.stubFor(post(urlPathEqualTo("/api/activities/42/register"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"9001","activityId":"42","activityTitle":"中间件测试","name":"liulinjie","phone":"13800138000","status":"CONFIRMED"}
                                """)));

        when(activityRerankService.rerankWithReasons(anyList(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ActivityCandidate> c = invocation.getArgument(0);
                    int topK = invocation.getArgument(2, Integer.class);
                    return c.stream().limit(topK).map(x -> new RerankedActivity(x.id(), "ok")).toList();
                });
    }

    @AfterEach
    void clearCtx() {
        AgentContextHolder.clear();
    }

    @Test
    void searchThenRegister_ordinalOne_succeeds() {
        AgentContextHolder.set("s-it", "u-it");
        Object search = toolExecutor.execute("searchActivities", Map.of("city", "上海", "keyword", ""));
        assertThat(search.toString()).contains("【活动ID：");

        Map<String, Object> reg = new HashMap<>();
        reg.put("activityId", "1");
        reg.put("name", "liulinjie");
        reg.put("phone", "13800138000");
        reg.put("email", null);
        Object out = toolExecutor.execute("registerActivity", reg);
        assertThat(out.toString()).contains("报名成功");
    }

    @Test
    void registerActivity_integerLastIds_normalizesAndSucceeds() {
        AgentContextHolder.set("s2", "u2");
        memoryService.setWorkingMemoryKey("s2", "u2", "lastActivityIds", List.of(42));

        Map<String, Object> reg = new HashMap<>();
        reg.put("activityId", "1");
        reg.put("name", "a");
        reg.put("phone", "13800138000");
        reg.put("email", null);
        Object out = toolExecutor.execute("registerActivity", reg);
        assertThat(out.toString()).contains("报名成功");
    }

    /**
     * 复现 trace 类问题：用户已明文给出 activityId，但工作记忆里无 searchActivities 写入的 lastActivityIds 时，
     * 必须拒绝下游调用并返回固定话术（否则会误报成功或打到错误活动）。
     */
    @Test
    void registerActivity_rejectsHallucinatedNamePhoneWhenUserBlobPresent() {
        AgentContextHolder.set("s-hall", "u-hall");
        AgentContextHolder.setCurrentTurnUserMessage("就报编号5那场吧");
        memoryService.setWorkingMemoryKey("s-hall", "u-hall", "lastActivityIds", List.of(42));

        Map<String, Object> reg = new HashMap<>();
        reg.put("activityId", "42");
        reg.put("name", "张三");
        reg.put("phone", "13800138000");
        reg.put("email", null);

        Object out = toolExecutor.execute("registerActivity", reg);

        assertThat(out.toString()).contains("本人提供");
        assertThat(AgentContextHolder.getErrorDetail()).contains("用户原话");
        WM.verify(0, postRequestedFor(urlPathMatching("/api/activities/.*/register")));
    }

    @Test
    void registerActivity_explicitId_withoutLastIds_rejectsAndDoesNotHitDownstream() {
        AgentContextHolder.set("s-no-list", "u-no-list");

        Map<String, Object> reg = new HashMap<>();
        reg.put("activityId", "28");
        reg.put("name", "alan");
        reg.put("phone", "123213123123");
        reg.put("email", null);

        Object out = toolExecutor.execute("registerActivity", reg);

        assertThat(out.toString()).contains("请先让助手推荐活动");
        assertThat(AgentContextHolder.getErrorDetail()).contains("无 lastActivityIds");

        WM.verify(0, postRequestedFor(urlPathMatching("/api/activities/.*/register")));
    }

    /** 先 search 写入 lastActivityIds 后，用户直接传「活动ID」字符串也应能报名（与仅传序号 1 对称）。 */
    @Test
    void searchThenRegister_explicitActivityId_succeeds() {
        AgentContextHolder.set("s-exp", "u-exp");
        toolExecutor.execute("searchActivities", Map.of("city", "上海", "keyword", ""));

        Map<String, Object> reg = new HashMap<>();
        reg.put("activityId", "42");
        reg.put("name", "liulinjie");
        reg.put("phone", "13800138000");
        reg.put("email", null);
        Object out = toolExecutor.execute("registerActivity", reg);
        assertThat(out.toString()).contains("报名成功");
    }

    /** 编排器每轮会 updateWorkingMemory；实现上应合并写入，不能清空 search 已写入的 lastActivityIds。 */
    @Test
    void updateWorkingMemory_preservesLastActivityIds() {
        String sid = "s-merge";
        String uid = "u-merge";
        memoryService.setWorkingMemoryKey(sid, uid, "lastActivityIds", List.of("42"));
        memoryService.updateWorkingMemory(sid, uid, "用户补充：姓名 alan");

        Map<String, Object> wm = memoryService.getWorkingMemory(sid, uid).orElseThrow();
        assertThat(LastActivityIdsSupport.normalize(wm.get("lastActivityIds"))).containsExactly("42");
        @SuppressWarnings("unchecked")
        List<String> history = (List<String>) wm.get("history");
        assertThat(history).contains("用户补充：姓名 alan");
    }
}
