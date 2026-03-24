# AGENTS.md

## Cursor Cloud specific instructions

### Project Overview
Java 17 / Spring Boot 3.2.1 multi-module Maven project (5 modules: `common`, `ai-gateway`, `agent-core`, `rag-service`, `legacy-dummy`). See `README.md` for architecture, ports, and commands.

### Build
```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn clean install -DskipTests
```

### Running Services (local profile, no middleware)
Start each in a separate terminal. The `local` profile is the default and requires **no** Docker/Redis/MySQL/Milvus:
```
mvn spring-boot:run -pl legacy-dummy -Dspring-boot.run.jvmArguments="-Dspring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
mvn spring-boot:run -pl rag-service
mvn spring-boot:run -pl agent-core
mvn spring-boot:run -pl ai-gateway
```

**Gotcha — legacy-dummy in local profile:** The module has `spring-boot-starter-jdbc` + `mysql-connector-j` on the classpath but no `application-local.yml` to exclude DataSource auto-configuration. You **must** pass the JVM argument above to exclude `DataSourceAutoConfiguration`; otherwise it fails with "Failed to determine a suitable driver class".

### Running Tests
```
mvn test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='!com.returensea.agent.memory.RedisMemoryIntegrationTest'
```
- `RedisMemoryIntegrationTest` uses `@ActiveProfiles("middleware")` and requires a running Redis. Exclude it when running without Docker middleware.
- MySQL integration tests in `legacy-dummy` (`*MySql*Test`) are auto-skipped via JUnit Assumptions when MySQL is unavailable.
- E2E tests (`**/e2e/**`) are excluded by default surefire config. Run them explicitly with `mvn test -Pe2e -pl ai-gateway` after starting all 4 services + an LLM.

### Ports
| Service | Port |
|---|---|
| agent-core | 8080 |
| ai-gateway | 8081 |
| rag-service | 8082 |
| legacy-dummy | 8083 |

### LLM Dependency
- Activity recommendation queries work without LLM (keyword-based intent + built-in recommendation engine).
- Queries requiring LLM-generated responses (e.g., "查询北京的活动") will return a graceful fallback message ("LLM 调用失败") without a configured Ollama or OpenAI-compatible API.
- To enable full LLM functionality, configure `LLM_PROVIDER`, `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL_NAME` env vars for agent-core (see `agent-core/src/main/resources/application.yml`).

### Web UI
- Chat debug page: `http://localhost:8081/app/`
- LangGraph Studio: `http://localhost:8081/?instance=intent-routing`
