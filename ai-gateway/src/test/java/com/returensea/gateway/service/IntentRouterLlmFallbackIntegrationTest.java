package com.returensea.gateway.service;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.IntentType;
import com.returensea.gateway.GatewayTestConfig;
import com.returensea.gateway.dto.ChatRequest;
import com.returensea.gateway.dto.RouteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.EventPublishingTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextBeforeModesTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图路由「低置信度时 LLM 兜底」集成测试：注入 LLM 桩，验证关键词低置信度时由 LLM 结果覆盖。
 */
@SpringBootTest(classes = {GatewayTestConfig.class, IntentRouterLlmFallbackIntegrationTest.LlmStubConfig.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ai.gateway.intent.low-confidence-threshold=0.8",
        "ai.gateway.intent.llm.enabled=true"
})
@TestExecutionListeners(
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS,
        listeners = {
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextBeforeModesTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class,
                EventPublishingTestExecutionListener.class
        }
)
class IntentRouterLlmFallbackIntegrationTest {

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

    @Test
    @DisplayName("模糊句「帮我看看」关键词得闲聊 0.7 < 0.8，LLM 桩返回活动查询，最终走活动查询且不需澄清")
    void lowConfidence_thenLlmOverride() {
        RouteResult result = intentRouterService.route(request("帮我看看"));

        assertThat(result.getIntentType()).isEqualTo(IntentType.ACTIVITY_QUERY);
        assertThat(result.getTargetAgent()).isEqualTo(AgentType.ACTIVITY);
        assertThat(result.getConfidence()).isEqualTo(0.85);
        assertThat(result.isNeedsClarification()).isFalse();
        assertThat(result.getClarification()).isNull();
    }

    @Test
    @DisplayName("LLM 桩返回带 city 实体时，结果中应带 extractedEntities")
    void llmStubReturnsEntity() {
        RouteResult result = intentRouterService.route(request("有啥好的"));

        assertThat(result.getIntentType()).isEqualTo(IntentType.ACTIVITY_QUERY);
        assertThat(result.getExtractedEntities()).containsEntry("city", "北京");
    }

    /**
     * 测试用 LLM 桩：固定返回 ACTIVITY_QUERY、0.85，并带 city=北京。
     */
    @Configuration
    static class LlmStubConfig {
        @Bean
        public LlmIntentClassifier llmIntentClassifier() {
            return message -> Optional.of(
                    new LlmIntentClassifier.LlmIntentResult(
                            IntentType.ACTIVITY_QUERY,
                            0.85,
                            Map.of("city", "北京")
                    )
            );
        }
    }
}
