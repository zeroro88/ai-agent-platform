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
- 记忆：`MemoryServiceImpl` 在 **无 Redis** 时退化为进程内 `ConcurrentHashMap`；**middleware** 且 Redis 可用时，Working / Session / 用户画像 / 长时记忆列表 / 槽位等键值均写入 Redis（键前缀 `agent:working:`、`agent:session:` 等）。长时向量检索在 middleware 下另接 Milvus（可选）。

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

# 2. 以 middleware profile 启动各服务（用 spring-boot 插件参数，避免 Maven 误解析）
mvn spring-boot:run -pl ai-gateway -Dspring-boot.run.profiles=middleware
mvn spring-boot:run -pl agent-core -Dspring-boot.run.profiles=middleware
mvn spring-boot:run -pl rag-service -Dspring-boot.run.profiles=middleware
mvn spring-boot:run -pl legacy-dummy -Dspring-boot.run.profiles=middleware
```

**重要：** 请使用 **`-Dspring-boot.run.profiles=middleware`**。不要写 `mvn ... -Dspring.profiles.active=middleware`（该 `-D` 只作用于 Maven，应用常仍落在默认 **`local`**）；也不要写 `mvn ... -- -Dspring.profiles.active=...`（`--` 后内容常被 Maven 当成生命周期阶段，会报 `Unknown lifecycle phase`）。

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

### HTTP 端到端（真实服务 + LLM）

依赖需**预先启动**（Docker、legacy-dummy、rag-service、agent-core、ai-gateway、LLM 配置），测试类通过 **`POST http://localhost:8081/api/v1/chat`** 走完整用户链路，**不**随默认 `mvn test` 执行。

- **源码**：[`ai-gateway/src/test/java/com/returensea/gateway/e2e/CriticalUserJourneysHttpE2EIT.java`](ai-gateway/src/test/java/com/returensea/gateway/e2e/CriticalUserJourneysHttpE2EIT.java)（类注释含启动清单与端口说明）
- **执行**：先按上文启动各服务，再运行  
  `mvn test -Pe2e -pl ai-gateway`
- **网关地址**：环境变量 `E2E_GATEWAY_BASE_URL` 或 `-De2e.gateway.baseUrl=...`（默认 `http://localhost:8081`）
- **Surefire**：父 POM 默认排除测试路径 `**/e2e/**`；`-Pe2e` 仅在 `ai-gateway` 中改为只包含 `e2e` 包下用例
- **Redis**：[`CriticalUserJourneysHttpE2EIT`](ai-gateway/src/test/java/com/returensea/gateway/e2e/CriticalUserJourneysHttpE2EIT.java) 中「链路2b」在 agent-core 使用 **middleware** 且本机可连 Redis 时，用 Lettuce 读取 `agent:slots:…:ACTIVITY_REGISTER` 与 `agent:session:…` 校验报名槽位与会话原文（姓名/手机）。地址覆盖：`E2E_REDIS_HOST`、`E2E_REDIS_PORT`（默认 `localhost:6379`）。连不上 Redis 时该用例会 **跳过**（Assumptions）。单元级校验另见 [`RedisMemoryIntegrationTest`](agent-core/src/test/java/com/returensea/agent/memory/RedisMemoryIntegrationTest.java)。

## 故障排查

- **traceId**：响应/前端中的 `traceId` 与各服务日志一致，可用 `grep "<traceId>" logs/*.log` 在 `ai-agent-platform/logs/` 下定位。
- **本地日志**：`mvn spring-boot:run` 时，gateway / agent-core / legacy-dummy / rag-service 的日志会写入 `ai-agent-platform/logs/` 下对应 `*.log` 文件。
- **Connection refused（下游服务）**：报名需 legacy-dummy (8083)，RAG 需 rag-service (8082)。可在 agent-core 的 `application.yml` 或环境变量中配置 `legacy.service.url`、RAG 服务地址。
- **middleware 下连不上中间件**：确认 Docker 已启动且对应容器在跑（`docker ps`）。Redis 未起时 agent-core 会报 Redis 连接失败；MySQL 未起时 legacy-dummy 集成测试会失败；Milvus 未起时 rag-service 会报 gRPC 连接失败。
- **MySQL 中文乱码**：`docker-compose` 已为 MySQL 指定 `--character-set-server=utf8mb4`，init 脚本开头含 `SET NAMES utf8mb4`。若**旧数据卷**已是乱码，需删卷重建：`docker compose -f docker/docker-compose.yml down -v` 后再 `up -d mysql`（会丢失本地库数据）。客户端（DataGrip 等）连接请使用 UTF-8 / `useUnicode=true`，避免用 Latin1 查看 utf8mb4 列。

## 核心能力（与代码一致）

- **双轨**：快轨（Agent 对话/推荐/咨询）+ 慢轨（传统 API 如报名）。
- **权限 L0–L3**：L0 自动执行，L1 建议+确认，L2 二次确认（如报名），L3 禁止自动执行。
- **三层记忆**：键值层（Working、Session、画像、长时列表、槽位）在 local 为进程内；middleware 下经 Redis。长时向量检索在 middleware 下可选 Milvus。
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
