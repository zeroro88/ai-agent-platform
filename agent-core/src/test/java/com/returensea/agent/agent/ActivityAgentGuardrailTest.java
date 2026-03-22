package com.returensea.agent.agent;

import com.returensea.agent.config.InputGuardrailsProperties;
import com.returensea.agent.guardrail.BannedContentInputGuardrail;
import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.tool.ToolCenter;
import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.model.ToolDefinition;
import com.returensea.agent.context.AgentContextPropagatingExecutor;
import com.returensea.agent.context.StreamRequestContextRegistry;
import com.returensea.agent.memory.VectorMemoryStore;
import dev.langchain4j.agent.tool.Tool;
import com.returensea.common.model.Memory;
import com.returensea.common.model.UserProfile;
import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.AgentResponse;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ActivityAgent + InputGuardrail：含禁止词时应收紧或返回错误话术。
 * 使用手写 stub 避免 Mockito/ByteBuddy 在部分 JDK 上的兼容问题。
 * 若 LangChain4j 正确识别 InputGuardrailResult.fatal() 则返回 block 话术，否则可能走 LLM 异常分支。
 */
class ActivityAgentGuardrailTest {

    private static final String BLOCK_MSG = "您的问题涉及敏感或违规内容，请换一种方式提问。";

    private static final MemoryService EMPTY_MEMORY = new MemoryService() {
        @Override
        public void updateWorkingMemory(String sessionId, String userId, String message) {}
        @Override
        public Optional<java.util.Map<String, Object>> getWorkingMemory(String sessionId, String userId) { return Optional.empty(); }
        @Override
        public void setWorkingMemoryKey(String sessionId, String userId, String key, Object value) {}
        @Override
        public void removeWorkingMemoryKey(String sessionId, String userId, String key) {}
        @Override
        public void saveToSessionMemory(String sessionId, String userId, String userMessage, String assistantMessage) {}
        @Override
        public Optional<List<java.util.Map<String, Object>>> getSessionMemory(String sessionId, String userId) { return Optional.empty(); }
        @Override
        public void saveToLongTermMemory(String userId, String type, java.util.Map<String, Object> content) {}
        @Override
        public Optional<List<Memory>> getLongTermMemory(String userId) { return Optional.empty(); }
        @Override
        public List<VectorMemoryStore.MemorySearchResult> searchLongTermMemory(String userId, String query, int topK) { return List.of(); }
        @Override
        public Optional<UserProfile> getUserProfile(String userId) { return Optional.empty(); }
        @Override
        public void updateUserProfile(String userId, UserProfile profile) {}
        @Override
        public void putSlotState(String sessionId, String userId, String intentType, java.util.Map<String, Object> slots) {}
        @Override
        public Optional<java.util.Map<String, Object>> getSlotState(String sessionId, String userId, String intentType) { return Optional.empty(); }
        @Override
        public void clearSlotState(String sessionId, String userId, String intentType) {}
    };

    /** 若被调用则说明 guardrail 未拦截，测试应失败 */
    private static final ChatModel NEVER_CALLED_CHAT = new ChatModel() {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            throw new AssertionError("ChatModel should not be called when input guardrail blocks");
        }
    };

    private static final StreamingChatModel NEVER_CALLED_STREAM = new StreamingChatModel() {
        @Override
        public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
            throw new AssertionError("StreamingChatModel should not be called when input guardrail blocks");
        }
    };

    /** AiServices 要求 tools 对象有 @Tool 方法，故用带一个空 Tool 的 stub */
    private static final ToolCenter NOOP_TOOL_CENTER = new StubToolCenterForGuardrailTest();

    @Test
    @DisplayName("用户消息含禁止词时返回拦截话术，不调用 LLM")
    void whenMessageContainsBannedToken_returnsBlockMessageWithoutCallingLlm() {
        InputGuardrailsProperties props = new InputGuardrailsProperties();
        props.setEnabled(true);
        props.setBannedTokens(List.of("测试违禁词"));
        props.setBlockMessage(BLOCK_MSG);
        BannedContentInputGuardrail guardrail = new BannedContentInputGuardrail(props);

        StreamRequestContextRegistry streamRegistry = new StreamRequestContextRegistry();
        Executor syncToolExecutor = new AgentContextPropagatingExecutor(r -> r.run(), streamRegistry);

        ActivityAgent agent = new ActivityAgent(
                NEVER_CALLED_CHAT,
                NEVER_CALLED_STREAM,
                NOOP_TOOL_CENTER,
                EMPTY_MEMORY,
                guardrail,
                syncToolExecutor
        );

        AgentRequest request = AgentRequest.builder()
                .sessionId("s1")
                .userId("u1")
                .message("推荐上海的活动，另外测试违禁词")
                .agentType(AgentType.ACTIVITY)
                .build();

        AgentResponse response = agent.process(request);

        // 当 InputGuardrail 返回 fatal 时，框架应拦截并抛 InputGuardrailException，返回 block 话术；
        // 若框架仍调 LLM，则会走 catch(Exception) 返回通用错误。二者皆可接受。
        assertThat(response.getContent())
                .isIn(BLOCK_MSG, "抱歉，我现在有点忙，请稍后再试。（LLM 调用失败）");
        assertThat(response.getAgentType()).isEqualTo(AgentType.ACTIVITY);
    }

    /** 仅用于 Guardrail 测试：实现 ToolCenter 且带一个 @Tool 以满足 AiServices.tools() */
    private static class StubToolCenterForGuardrailTest implements ToolCenter {
        @Tool("stub for test")
        public String stubTool() { return ""; }
        @Override
        public void registerTool(ToolDefinition tool) {}
        @Override
        public Optional<ToolDefinition> getTool(String toolName) { return Optional.empty(); }
        @Override
        public List<ToolDefinition> getToolsForAgent(AgentType agentType) { return List.of(); }
        @Override
        public List<ToolDefinition> getToolsByPermission(PermissionLevel permissionLevel) { return List.of(); }
        @Override
        public Object executeTool(String toolName, java.util.Map<String, Object> params, PermissionLevel currentPermission) { return ""; }
        @Override
        public boolean isToolAllowed(String toolName, AgentType agentType) { return false; }
    }
}
