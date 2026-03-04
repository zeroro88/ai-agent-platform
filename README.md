# AI Agent 海归服务平台

基于 AI-Agent-海归服务平台-架构设计.md 构建的智能服务平台。

## 架构图

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                    用户层                                        │
│  ┌─────────────┐                         ┌─────────────┐                        │
│  │  微信小程序   │                         │   H5/公众号   │                        │
│  └──────┬──────┘                         └──────┬──────┘                        │
└─────────┼───────────────────────────────────────┼───────────────────────────────┘
          │                                       │
          ▼                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                智能网关层 (AI Gateway)                          │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────────────┐    │
│  │  鉴权/限流/审计   │ → │   意图识别路由    │ → │   Agent 分发与调度       │    │
│  │  GatewayPolicy   │   │  IntentRouter    │   │   (快轨/慢轨/混合)        │    │
│  └─────────────────┘   └─────────────────┘   └─────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
          │                                        │
          ▼                                        ▼
┌─────────────────────────────────┐    ┌─────────────────────────────────────────┐
│       快轨 (AI Agent)           │    │           慢轨 (传统事务)                │
│  ┌────────────────────────────┐ │    │  ┌────────┐ ┌────────┐ ┌────────┐     │
│  │     Orchestrator           │ │    │  │ 活动服务│ │政策服务│ │订单服务│     │
│  │    (任务图编排)            │ │    │  └────────┘ └────────┘ └────────┘     │
│  └────────────┬───────────────┘ │    │                                         │
│               │                  │    │  传统 API (支付/认证等关键操作)          │
│  ┌────────────┼───────────────┐ │    │                                         │
│  │          三层记忆           │ │    └─────────────────────────────────────────┘
│  │  ┌────────┴────────┐      │ │
│  │ │ Working │ Session │      │ │
│  │ │ (进程内) │ (Redis) │      │ │
│  │ └────────┴────────┘      │ │
│  │  ┌───────────────┐        │ │
│  │  │ Long-term     │        │ │
│  │  │ (向量库)      │        │ │
│  │  └───────────────┘        │ │
│  └────────────┬───────────────┘ │
│               │                  │
│  ┌────────────┼───────────────┐ │
│  │      领域 Agent             │ │
│  │ ┌─────────┼─────────┐     │ │
│  │ │ Activity│ Policy  │ Op  │ │
│  │ │ Agent   │ Agent   │     │ │
│  │ └─────────┴─────────┘     │ │
│  └────────────┬───────────────┘ │
│               │                  │
│  ┌────────────┼───────────────┐ │
│  │      Tool Center           │ │
│  │  L0-L3 权限分级控制        │ │
│  │  • L0: 自动执行 (问答)     │ │
│  │  • L1: 提建议+确认         │ │
│  │  • L2: 二次确认执行        │ │
│  │  • L3: 禁止自动执行        │ │
│  └────────────────────────────┘ │
└─────────────────────────────────┘
```

## 技术栈

- **后端框架**: Spring Boot 3.2 + Java 17
- **AI 框架**: LangChain4j (主选) / Spring AI (可选)
- **LLM**: DeepSeek V3 (可扩展多模型)
- **向量库**: pgvector / Milvus
- **缓存**: Redis
- **工作流**: Dify (运营侧)

## 技术选型说明

### LangChain4j (当前使用)

用于 **agent-core** 和 **rag-service** 模块：

| 模块 | 依赖 | 用途 |
|------|------|------|
| agent-core | langchain4j, langchain4j-open-ai | Agent 编排、LLM 调用、Tool 定义 |
| rag-service | langchain4j-embeddings | 向量嵌入、文档分块 |

```xml
<!-- agent-core/pom.xml -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>

<!-- rag-service/pom.xml -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-embeddings</artifactId>
</dependency>
```

### Spring AI (预留扩展)

当前项目**未引入** Spring AI 依赖，但架构预留支持：

- 场景：若后续需要与 Spring Cloud 全家桶深度集成
- 替换路径：LangChain4j → Spring AI（仅需替换依赖和少量接口适配）
- C 端 Agent 编排保持在**应用内**（不用 Dify），便于细粒度控制

### 选择理由

1. **LangChain4j 成熟度高**：生态丰富，少踩坑
2. **Spring Boot 栈优先**：LangChain4j 与 Spring Boot 集成良好
3. **C 端链路可控**：编排在应用内，首 token 延迟更可控
4. **Dify 用于运营侧**：固定流程，与核心交易解耦

## 模块说明

| 模块 | 端口 | 说明 |
|------|------|------|
| ai-gateway | 8080 | AI 网关，意图路由与请求分发 |
| agent-core | 8081 | Agent 核心编排，领域 Agent |
| rag-service | 8082 | RAG 知识库检索 |
| legacy-dummy | 8083 | 传统服务 (Dummy) |

## 快速开始

### 1. 构建项目

```bash
cd ai-agent-platform
mvn clean install
```

### 2. 启动服务

```bash
# 启动 AI Gateway
mvn spring-boot:run -pl ai-gateway

# 启动 Agent Core
mvn spring-boot:run -pl agent-core

# 启动 RAG Service
mvn spring-boot:run -pl rag-service

# 启动 Legacy Dummy
mvn spring-boot:run -pl legacy-dummy
```

### 3. 测试接口

```bash
# 发送聊天请求
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "帮我推荐北京的活动",
    "sessionId": "test-001",
    "userId": "user-001"
  }'
```

## 故障排查

### 用 traceId 在日志中定位

前端/接口返回的 `traceId`（如 `a0508a0ce9384259`）与本次请求在服务端日志中一致，便于排查。

**在 agent-core 或 ai-gateway 日志中搜索：**

```bash
# 在控制台输出或日志文件中
grep "a0508a0ce9384259" <日志文件或终端输出>
```

日志格式为 `[traceId] 消息`，例如：`[a0508a0ce9384259] Processing stream request: 我想报名活动`。

### 本地测试时的统一日志文件

本地用 `mvn spring-boot:run` 启动时，gateway、core、legacy 三个服务的日志会写入 **`ai-agent-platform/logs/`** 目录下的单独文件，便于用 traceId 或 grep 排查：

| 服务 | 日志文件 |
|------|----------|
| agent-core | `logs/agent-core.log` |
| ai-gateway | `logs/ai-gateway.log` |
| legacy-dummy | `logs/legacy-dummy.log` |

示例：按 traceId 在全部本地日志中搜索：`grep "a0508a0ce9384259" ai-agent-platform/logs/*.log`

### Connection refused（连接被拒绝）

若用户看到“尝试再次连接时出现了 Connection refused”或类似提示，说明 **agent-core 调用下游服务时无法连通**。

| 场景 | 下游服务 | 默认地址 | 处理方式 |
|------|----------|----------|----------|
| **报名活动**（registerActivity） | legacy-dummy | `http://localhost:8083` | 启动：`mvn spring-boot:run -pl legacy-dummy` |
| **查政策/知识库**（RAG） | rag-service | `http://localhost:8082` | 启动：`mvn spring-boot:run -pl rag-service` |

- 报名流程报错时，优先确认 **legacy-dummy (8083)** 已启动。
- 配置可覆盖：`agent-core` 的 `application.yml` 或环境变量 `legacy.service.url`、`rag-service.base-url`（若使用非默认端口或主机）。

## 核心特性

### 1. 双轨架构
- **快轨 (Fast Track)**: AI Agent 处理对话、推荐、咨询
- **慢轨 (Slow Track)**: 传统事务处理，支付/认证等
- **混合 (Hybrid)**: AI 辅助收集参数，传统执行

### 2. 权限分级 (L0-L3)

| 等级 | 权限 | 示例 |
|------|------|------|
| L0 | 自动执行 | 政策问答、活动介绍 |
| L1 | 提建议+确认 | 收藏、订阅 |
| L2 | 二次确认 | 报名、取消 |
| L3 | 禁止自动执行 | 支付、退款、认证 |

### 3. 三层记忆

- **Working Memory**: 进程内，当前会话上下文
- **Session Memory**: Redis，30分钟 TTL
- **Long-term Memory**: 向量库，永久存储用户画像

### 4. RAG 检索链路

1. 意图理解 + 关键词提取
2. 元数据过滤 (城市/时间/标签)
3. 向量检索 (Top K ≥ 3)
4. LLM 生成 + 引用标注 (包含 Chunk ID)

#### RAG 引用格式

检索结果包含段落 ID，便于溯源：

```json
{
  "answer": "根据检索到的信息：\n\n[政策内容]...\n\n---\n📚 参考来源：\n• 北京海归落户政策 (段落 ID: doc-1-a1b2c3d4)",
  "chunks": [{
    "chunkId": "doc-1-a1b2c3d4",
    "documentId": "doc-1",
    "documentTitle": "北京海归落户政策",
    "content": "...",
    "score": 0.92
  }]
}
```

#### 多级检索

- **Level 1**: 向量相似度检索 (基础)
- **Level 2**: 元数据过滤 (城市/类别/标签)
- **Level 3**: 重排序 (Rerank)

### 5. 工具集成 (Tool Center)

ToolExecutor 通过 HTTP 调用 legacy-dummy 服务：

```
Agent → ToolExecutor → HTTP → legacy-dummy (ActivityController)
```

支持的操作：
- `searchActivities`: 搜索活动
- `getActivityDetail`: 获取活动详情
- `registerActivity`: 报名活动
- `queryOrder`: 查询订单

### 6. 长时记忆 (向量库)

Long-term Memory 支持语义检索：

```
User Profile → VectorMemoryStore → Semantic Search → 相关历史
```

接口定义：
```java
public interface VectorMemoryStore {
    void addMemory(String userId, String content, Map<String, Object> metadata);
    List<MemorySearchResult> search(String userId, String query, int topK);
    void deleteMemory(String userId, String memoryId);
}
```

### 7. Slot 填槽 (参数提取)

结构化参数提取，支持意图所需的关键参数：

```java
SlotFillingRequest request = SlotFillingRequest.builder()
    .intentType("ACTIVITY_REGISTER")
    .userMessage("我想报名海归招聘会，叫张三，电话13800138000")
    .currentSlots(new HashMap<>())
    .build();

SlotFillingResult result = slotFillingService.fillSlots(request);
// result.getFilledSlots() = {activityId: null, name: "张三", phone: "13800138000"}
// result.getMissingSlots() = ["activityId"]
// result.getClarificationMessage() = "请提供您要报名的活动ID。"
```

### 8. 推荐引擎

基于用户行为的智能推荐：

```java
// 记录用户行为
recommendationService.recordUserAction(userId, "view", Map.of(
    "type", "ACTIVITY",
    "city", "北京",
    "category", "创业"
));

// 生成推荐
List<Recommendation> recs = recommendationService.recommend(userId, "海归创业", 3);
```

推荐算法：
- 向量语义匹配
- 用户偏好加权
- 实时行为反馈

### 9. 灰度路由

特性开关控制灰度发布：

```java
// 检查特性是否开启
if (grayRoutingService.isFeatureEnabled(userId, "new-recommendation")) {
    // 使用新推荐算法
}
```

预置灰度规则：
- `new-recommendation`: 10% 流量
- `slot-filling`: 20% 流量
- `vector-memory`: 15% 流量

## 配置说明

### AI Gateway

```yaml
ai:
  gateway:
    route:
      default-service: agent-core
      timeout-ms: 30000
    rate-limit:
      enabled: true
      requests-per-minute: 60
```

### Agent Core

```yaml
ai:
  agent:
    max-iterations: 5
    timeout-seconds: 30
    memory:
      session-ttl-minutes: 30
```

## 演进路线

1. **Phase 1**: AI Gateway + RAG 基线
2. **Phase 2**: Agent Framework + 政策/活动 Agent
3. **Phase 3**: 推荐引擎 + 报名引导
4. **Phase 4**: 运营工作流 + 内容智能
5. **Phase 5**: 成本治理 + 规模化

## License

MIT
