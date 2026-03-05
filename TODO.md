# 实施计划：LangChain4j Guardrails（方案 B）与可选 LangGraph4j

> 记录步骤供后续实施，当前仅写计划不执行代码改动。

---

## 一、清理 JoySafety（若存在）

- [x] **1.1** 删除 JoySafety 相关类（若存在）  
  - `agent-core/.../config/JoySafetyProperties.java`  
  - `agent-core/.../joysafety/JoySafetyCheckRequest.java`  
  - `agent-core/.../joysafety/JoySafetyCheckResponse.java`  
  - `agent-core/.../joysafety/JoySafetyClient.java`  
  - `agent-core/.../joysafety/JoySafetyService.java`（若有）
- [x] **1.2** 从 `application.yml` 移除 `ai.agent.joysafety` 配置块
- [x] **1.3** 从 AgentController（或其它调用点）移除对 JoySafety 的依赖与「先检测再调 Orchestrator」的逻辑，恢复为直接调用 Orchestrator

---

## 二、方案 B：LangChain4j AiServices + Guardrails

### 2.1 依赖与配置

- [x] **2.1.1** 在 `agent-core/pom.xml` 中确认/添加：  
  - `langchain4j-guardrails`（或含 `MessageModeratorInputGuardrail` 的模块）  
  - 若使用 OpenAI Moderation：确保 `langchain4j-open-ai` 可用并配置 ModerationModel
- [x] **2.1.2** 在 `application.yml` 中为 ModerationModel（若用）增加配置项（如 API key、base-url）— 本次采用自定义 InputGuardrail，无需 ModerationModel

### 2.2 定义带 Guardrails 的 AiService

- [x] **2.2.1** 新建 AiService 接口（例如 `PolicyQaAssistant` 或通用 `AgentChatAssistant`）  
  - 方法签名示例：`String chat(String userMessage)` 或带 `List<ChatMessage> history`  
  - 在接口或方法上添加 `@InputGuardrails(...)`，例如 `MessageModeratorInputGuardrail.class`  
  - 在接口或方法上添加 `@OutputGuardrails(...)`（可选），如自定义输出校验或格式 Guardrail
- [x] **2.2.2** 新建 Spring 配置类或 `@Bean`：  
  - 使用 `AiServices.builder(AssistantClass.class).chatModel(chatModel).inputGuardrails(...).outputGuardrails(...).build()` 创建 AiService 实例  
  - 若需 Spring 管理 Guardrail 实例，可注册 LangChain4j 的 `ClassInstanceFactory`（或等价 SPI）从 Spring 容器取 Bean

### 2.3 在现有编排中接入 AiService

- [x] **2.3.1** 在 OrchestratorImpl（或负责政策/活动回复的 Agent）中：  
  - 选定一条链路（建议先政策问答）：原「直接调 LLM」处改为调用上述 AiService 的 `chat(...)`  
  - 入参：当前用户消息 + 必要上下文（可从 Memory 或 RAG 结果拼进 prompt）  
  - 将 AiService 返回的字符串转换为现有 `AgentResponse` 的 content 格式（如 `buildContentJson(text, "message")`）
- [x] **2.3.2** 流式场景（若有）：  
  - 评估是否使用 AiService 的流式方法（若 LangChain4j 支持），或保持该链路非流式、仅非流式路径走 Guardrails

### 2.4 保留并沿用 ContentSanitizer

- [x] **2.4.1** 保持现有 `ContentSanitizer`（banned-tokens/patterns）在最终响应上的后处理，与 OutputGuardrail 可同时生效（先 Guardrail 再 Sanitizer）

---

## 三、可选：引入 LangGraph4j

### 3.1 依赖

- [ ] **3.1.1** 在父 POM 或 `agent-core/pom.xml` 增加：  
  - `org.bsc.langgraph4j:langgraph4j-core`（版本 1.8.x，Java 17+）  
  - 若使用 ReAct/工具调用图：`langgraph4j-langchain4j`

### 3.2 用 StateGraph 替代部分 TaskGraph

- [ ] **3.2.1** 定义 LangGraph4j 的 `AgentState`（或继承类）：  
  - 包含与当前请求/响应相关的字段（如 messages、intent、slotState、当前回复文本等），与现有 `AgentRequest`/`AgentResponse` 可做映射
- [ ] **3.2.2** 将「政策问答」或整条 Agent 流程建模为 `StateGraph`：  
  - 节点 1：意图识别/路由（可复用现有意图逻辑）  
  - 节点 2：政策/活动/运营等（节点内调用 2.2 的 AiService，带 Guardrails）  
  - 节点 3：汇总或工具执行等  
  - 边：`addEdge` / `addConditionalEdges` 按现有 TaskGraph 逻辑连接
- [ ] **3.2.3** 编译并执行：  
  - `stateGraph.compile()` 得到 `CompiledGraph`  
  - 在 Controller 或 Orchestrator 中，用 `compiledGraph.invoke(initialState)` 或 `stream(...)` 替代当前 `executeTaskGraph` 的调用
- [ ] **3.2.4** 若需持久化/重放：配置 `CheckpointSaver`（如 MemorySaver 或自实现 Redis/DB）

### 3.3 文档与可观测

- [ ] **3.3.1** 在 README 或架构文档中注明：编排采用 LangGraph4j StateGraph，单步对话采用 LangChain4j AiServices + Input/Output Guardrails  
- [ ] **3.3.2** 如需图可视化：使用 LangGraph4j 的 PlantUML/Mermaid 导出能力，将生成的图放入文档或监控

---

## 四、验收与回滚

- [ ] **4.1** 验收：政策问答（或所选链路）请求经 InputGuardrail 拦截敏感输入、OutputGuardrail 校验输出；正常请求返回与改前一致  
- [ ] **4.2** 回滚：保留原 OrchestratorImpl 分支或 feature flag，可切回「直接调 LLM + 仅 ContentSanitizer」路径

---

## 五、实施顺序建议

1. 先完成 **第二章（方案 B）**：单链路 AiService + Guardrails，不引入 LangGraph4j。  
2. 验证通过后，再按需实施 **第三章（LangGraph4j）**，将编排迁到 StateGraph。
