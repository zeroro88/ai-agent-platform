# AI Agent 服务平台

基于 Spring Boot + LangChain4j 的智能对话与活动服务。

## 架构概览

```
用户 → AI Gateway (8081) → Agent Core (8080) → 领域 Agent + Tool Center
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
- **Profile**：默认 `local`（无中间件强依赖）；`middleware` 下使用 Redis / MySQL / Milvus 真实接入
- 记忆：Working（进程内）；Session/槽位（local 内存，middleware 用 Redis）；长时/向量（local 内存，middleware 用 Milvus）

## 模块与端口

| 模块         | 端口 | 说明                         |
|--------------|------|------------------------------|
| ai-gateway   | 8081 | 网关、路由、调试页 /app、Studio |
| agent-core   | 8080 | Agent 编排、调试接口         |
| rag-service  | 8082 | RAG 检索                     |
| legacy-dummy | 8083 | 活动/报名模拟                |

## 快速开始

```bash
cd ai-agent-platform
mvn clean install -DskipTests
```

启动服务（可多终端或后台）：

**默认 local（不依赖 Docker 中间件）：**
```bash
mvn spring-boot:run -pl ai-gateway
mvn spring-boot:run -pl agent-core
mvn spring-boot:run -pl rag-service
mvn spring-boot:run -pl legacy-dummy
```

**使用真实中间件（需先启动 Docker）：**
```bash
# 1. 启动中间件（在 ai-agent-platform 目录下）
docker compose -f docker/docker-compose.yml up -d mysql redis    # 活动/报名 + 记忆
docker compose -f docker/docker-compose.yml up -d milvus         # 可选：RAG 向量库

# 2. 以 middleware profile 启动各服务
mvn spring-boot:run -pl ai-gateway -- -Dspring.profiles.active=middleware
mvn spring-boot:run -pl agent-core -- -Dspring.profiles.active=middleware
mvn spring-boot:run -pl rag-service -- -Dspring.profiles.active=middleware
mvn spring-boot:run -pl legacy-dummy -- -Dspring.profiles.active=middleware
```

流式聊天（经网关）：

```bash
curl -X POST http://localhost:8081/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"推荐上海的活动","sessionId":"test-001","userId":"user-001"}'
```

- 调试页（经 Gateway 外层调用）：http://localhost:8081/app/
- LangGraph Studio（意图路由图调试）：http://localhost:8081/?instance=intent-routing

## 中间件与集成测试

项目通过 `docker/docker-compose.yml` 提供 MySQL、Redis、Milvus（及 etcd/minio）等中间件。真实接入仅在 **`middleware` profile** 下生效；集成测试需先启动对应中间件。

**启动中间件后，跑全量回归（middleware）：**
```bash
cd ai-agent-platform
docker compose -f docker/docker-compose.yml up -d mysql redis milvus
mvn test -Dspring.profiles.active=middleware
```

**按模块单独验证：**
| 中间件 | 前置启动 | 测试命令 |
|--------|----------|----------|
| Redis | `docker compose -f docker/docker-compose.yml up -d redis` | `mvn -pl agent-core test -Dspring.profiles.active=middleware -Dtest=RedisMemoryIntegrationTest` |
| MySQL | `docker compose -f docker/docker-compose.yml up -d mysql` | `mvn -pl legacy-dummy test -Dspring.profiles.active=middleware "-Dtest=*Activity*Test,*Register*Test,*Order*Test"` |
| Milvus | `docker compose -f docker/docker-compose.yml up -d milvus` | `mvn -pl rag-service test -Dspring.profiles.active=middleware -Dtest=RAGMilvusIntegrationTest` |

## 故障排查

- **traceId**：响应/前端中的 `traceId` 与各服务日志一致，可用 `grep "<traceId>" logs/*.log` 在 `ai-agent-platform/logs/` 下定位。
- **本地日志**：`mvn spring-boot:run` 时，gateway / agent-core / legacy-dummy / rag-service 的日志会写入 `ai-agent-platform/logs/` 下对应 `*.log` 文件。
- **Connection refused（下游服务）**：报名需 legacy-dummy (8083)，RAG 需 rag-service (8082)。可在 agent-core 的 `application.yml` 或环境变量中配置 `legacy.service.url`、RAG 服务地址。
- **middleware 下连不上中间件**：确认 Docker 已启动且对应容器在跑（`docker ps`）。Redis 未起时 agent-core 会报 Redis 连接失败；MySQL 未起时 legacy-dummy 集成测试会失败；Milvus 未起时 rag-service 会报 gRPC 连接失败。

## 核心能力（与代码一致）

- **双轨**：快轨（Agent 对话/推荐/咨询）+ 慢轨（传统 API 如报名）。
- **权限 L0–L3**：L0 自动执行，L1 建议+确认，L2 二次确认（如报名），L3 禁止自动执行。
- **三层记忆**：Working（进程内）、Session/槽位（local 内存 / middleware Redis）、Long-term（local 内存向量 / middleware 可选 Milvus）。
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
