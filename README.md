# AI Agent 服务平台

基于 Spring Boot + LangChain4j 的智能对话与活动服务。

## 架构概览

```
用户 → AI Gateway (8080) → Agent Core (8081) → 领域 Agent + Tool Center
                                    ↓
              Legacy Dummy (8083) / RAG Service (8082)
```

- **ai-gateway**：鉴权、限流、意图路由、请求转发
- **agent-core**：任务图编排、领域 Agent（Activity）、三层记忆、Tool 执行
- **rag-service**：向量检索与政策/知识库 RAG
- **legacy-dummy**：活动 CRUD、报名等传统 API 模拟

## 技术栈

- Spring Boot 3.2 / Java 17
- LangChain4j（Agent 编排、LLM 调用、Embedding）
- LLM：Ollama / DeepSeek（OpenAI 兼容）
- 记忆：进程内 Working + Session（当前为内存 Map，可配 Redis）；长时记忆为内存向量（InMemoryEmbeddingStore）

## 模块与端口

| 模块         | 端口 | 说明           |
|--------------|------|----------------|
| ai-gateway   | 8080 | 网关、路由     |
| agent-core   | 8081 | Agent 编排     |
| rag-service  | 8082 | RAG 检索       |
| legacy-dummy | 8083 | 活动/报名模拟  |

## 快速开始

```bash
cd ai-agent-platform
mvn clean install -DskipTests
```

启动服务（可多终端或后台）：

```bash
mvn spring-boot:run -pl ai-gateway
mvn spring-boot:run -pl agent-core
mvn spring-boot:run -pl rag-service
mvn spring-boot:run -pl legacy-dummy
```

流式聊天（经网关）：

```bash
curl -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"推荐上海的活动","sessionId":"test-001","userId":"user-001"}'
```

## 故障排查

- **traceId**：响应/前端中的 `traceId` 与各服务日志一致，可用 `grep "<traceId>" logs/*.log` 在 `ai-agent-platform/logs/` 下定位。
- **本地日志**：`mvn spring-boot:run` 时，gateway / agent-core / legacy-dummy 的日志会写入 `ai-agent-platform/logs/` 下对应 `*.log` 文件。
- **Connection refused**：多为 agent-core 连不上下游。报名需 legacy-dummy (8083)，RAG 需 rag-service (8082)。可在 agent-core 的 `application.yml` 或环境变量中配置 `legacy.service.url`、RAG 服务地址。

## 核心能力（与代码一致）

- **双轨**：快轨（Agent 对话/推荐/咨询）+ 慢轨（传统 API 如报名）。
- **权限 L0–L3**：L0 自动执行，L1 建议+确认，L2 二次确认（如报名），L3 禁止自动执行。
- **三层记忆**：Working（进程内）、Session（可配 Redis，当前内存）、Long-term（向量，当前内存实现）。
- **工具**：searchActivities、getActivityDetail、registerActivity、createActivity、queryOrder（通过 HTTP 调 legacy-dummy）。
- **RAG**：rag-service 提供向量检索与引用；agent-core 可调用其接口。
- **Slot 填槽**：ACTIVITY_REGISTER 等意图的结构化参数提取。
- **推荐**：RecommendationService 行为记录与推荐；活动推荐后写入 lastActivityIds 供报名校验。
- **灰度**：ai-gateway 的 GrayRoutingService 支持按用户/流量做特性开关。

## 配置要点

- **agent-core**：`ai.agent.llm.*`（provider、base-url、model、temperature）、`ai.agent.output-guardrails`（banned-tokens/patterns）、`legacy.service.url`、RAG 服务 base-url。
- **ai-gateway**：`ai.gateway.route.*`、`ai.gateway.rate-limit.*`、`agent-core.base-url`。

## License

MIT
