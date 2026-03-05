package com.returensea.gateway.service;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.IntentType;
import com.returensea.gateway.GatewayTestConfig;
import com.returensea.gateway.dto.ChatRequest;
import com.returensea.gateway.dto.RouteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.event.EventPublishingTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图路由集成测试：验证「关键词 + 低置信度时 LLM 兜底」的混合行为。
 */
@SpringBootTest(classes = GatewayTestConfig.class)
@ActiveProfiles("test")
@TestExecutionListeners(
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS,
        listeners = {
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextBeforeModesTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class,
                EventPublishingTestExecutionListener.class
        }
)
class IntentRouterIntegrationTest {

    @Autowired
    private IntentRouterService intentRouterService;

    private static ChatRequest request(String message) {
        return ChatRequest.builder()
                .message(message)
                .userId("test-user")
                .sessionId("test-session")
                .traceId("trace-1")
                .build();
    }

    @Nested
    @DisplayName("仅关键词路径（LLM 未启用）")
    class KeywordOnly {

        @Test
        @DisplayName("强关键词命中：政策咨询，高置信度，不需澄清")
        void highConfidence_policyConsult() {
            RouteResult result = intentRouterService.route(request("上海落户政策有哪些？"));

            assertThat(result.getIntentType()).isEqualTo(IntentType.POLICY_CONSULT);
            assertThat(result.getTargetAgent()).isEqualTo(AgentType.POLICY);
            assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.6);
            assertThat(result.isNeedsClarification()).isFalse();
            assertThat(result.getClarification()).isNull();
        }

        @Test
        @DisplayName("强关键词命中：活动查询，高置信度")
        void highConfidence_activityQuery() {
            RouteResult result = intentRouterService.route(request("最近有什么活动？"));

            assertThat(result.getIntentType()).isEqualTo(IntentType.ACTIVITY_QUERY);
            assertThat(result.getTargetAgent()).isEqualTo(AgentType.ACTIVITY);
            assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.6);
            assertThat(result.isNeedsClarification()).isFalse();
        }

        @Test
        @DisplayName("弱/模糊表述：命中闲聊，置信度仍>=0.6 时不需要澄清")
        void chitchat_confidenceAtOrAboveThreshold() {
            RouteResult result = intentRouterService.route(request("随便聊聊"));

            assertThat(result.getIntentType()).isEqualTo(IntentType.CHITCHAT);
            assertThat(result.getTargetAgent()).isEqualTo(AgentType.ACTIVITY);
            assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.6);
        }
    }
}
