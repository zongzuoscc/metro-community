# Community Agent Foundation (Stage A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成一个默认全关、无 Key 可启动、不影响私信与人工审核的 Java AI 安全底座，为后续 revision/Outbox、Milvus 和 Agent runtime 提供稳定边界。

**Architecture:** 删除把私信、聊天、摘要、审核和搜索工具绑在一起的 AI 原型，以 `AiChatGateway` / `EmbeddingGateway` 隔离 Spring AI Provider 类型，并以 capability 为粒度提供开关、配额、截止时间、有界舱壁、外层重试、错误映射和指标。阶段 A 不建 Agent API/runtime，不修改文章数据模型；存量 `status=2` 就是过渡期人工待审真相。

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, MyBatis-Plus 3.5.17 Boot 3 Starter, Spring MVC + WebFlux client, Resilience4j, Micrometer/Prometheus, Redis/MySQL/RabbitMQ/Elasticsearch Testcontainers.

## Global Constraints

- 本计划只修改 Java/Maven/YAML/文档和测试，不调用 live DeepSeek、Ollama 或任何生产服务。
- 依赖版本精确为 Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17；移除 OpenAI M5 starter 和 Spring Milestone 仓库。
- `spring.ai.retry.max-attempts=1`；业务重试只能在 capability 外层发生，交互请求最多重试 1 次，后台任务最多 3 次。
- `metro.ai.enabled` 以及 agent/memory/writing/moderation/embedding 子开关均默认 `false`；缺 Key 不创建远程 Provider client。
- 模型名、base URL 和 Key 都来自 typed config/环境变量，Java 不硬编码。
- 最终验收不以 Mockito 结果作为证据；Provider 协议用进程内 JDK `HttpServer` stub，业务集成用真实 Testcontainers。
- 不添加 Flyway。本阶段无 schema 变更；后续仍使用 `docs/database/migrations/` 下幂等 forward SQL。
- 不实现 `article_revision`/通用 Outbox（B）、Milvus/Collection（C）、conversation/turn/SSE/Planner（D）。

---

## File map

**Delete in A:**

- `src/main/java/cumt/zongzuo/community/websocket/ChatUtils.java`
- `src/main/java/cumt/zongzuo/community/controller/AiController.java`
- `src/main/java/cumt/zongzuo/community/service/MetroAiService.java`
- `src/main/java/cumt/zongzuo/community/config/AiToolConfig.java`
- `src/main/java/cumt/zongzuo/community/mq/ArticleAuditConsumer.java`

**New provider/runtime boundary:**

- `src/main/java/cumt/zongzuo/community/ai/provider/` — capability enum, provider-neutral commands/results, gateway interfaces, DeepSeek/Ollama/disabled adapters and provider errors.
- `src/main/java/cumt/zongzuo/community/ai/config/MetroAiProperties.java` — all business flags, provider values and capability limits.
- `src/main/java/cumt/zongzuo/community/ai/config/AiProviderConfiguration.java` — conditional Provider construction with one internal attempt.
- `src/main/java/cumt/zongzuo/community/ai/runtime/` — invocation context, bounded executor, Redis quota, Resilience4j policies and Micrometer metrics.
- `src/main/java/cumt/zongzuo/community/ai/moderation/` — legacy-schema manual-review fallback only; structured/revision-bound moderation starts in B.
- `src/main/java/cumt/zongzuo/community/ai/web/` — marker annotation, AI exception, RFC 9457 factory/advice and AI-path security writer.

## Task 1: Decouple ordinary private messages from AI and remove robot `9999`

**Files:**

- Modify: `src/main/java/cumt/zongzuo/community/websocket/WebSocketServer.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/ChatServiceImpl.java`
- Delete: `src/main/java/cumt/zongzuo/community/websocket/ChatUtils.java`
- Modify: `src/test/java/cumt/zongzuo/community/websocket/WebSocketServerTest.java`
- Create: `src/test/java/cumt/zongzuo/community/websocket/PrivateMessageIntegrationTest.java`

**Interfaces:**

- `WebSocketServer(WebSocketTicketService, WebSocketSessionRegistry, ObjectMapper, ChatService)` receives a normal Spring service through the existing `AutowireCapableBeanFactory` endpoint configurator.
- `ChatService.sendChat(Long fromId, Long toId, String content)` remains the only private-message persistence boundary.
- No Java source may reference `ChatUtils`, `handleAiChatAsync`, or an AI-specific `toId == 9999L` branch.

- [ ] **Step 1: Write the failing WebSocket and real-container tests**

Add a unit assertion that a normal frame calls `chatService.sendChat(42L, 43L, "hello")` before `sendText`, and a Testcontainers test that seeds two users plus reciprocal `sys_follow` rows, opens two real `/im/{ticket}` connections, sends `{"toId":43,"content":"hello"}`, then asserts:

```java
await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM chat_msg WHERE from_id=42 AND to_id=43 AND content='hello'",
        Integer.class)).isEqualTo(1));
assertThat(receivedJson.path("fromId").asLong()).isEqualTo(42L);
assertThat(receivedJson.path("content").asText()).isEqualTo("hello");
assertThat(jdbcTemplate.queryForObject(
    "SELECT COUNT(*) FROM chat_msg WHERE from_id=9999 OR to_id=9999", Integer.class)).isZero();
```

Also assert `/api/chat/friends` for a user with no contacts does not synthesize id `9999` while all AI flags are false.

- [ ] **Step 2: Run red tests**

Run: `./mvnw -Dtest=WebSocketServerTest,PrivateMessageIntegrationTest test`

Expected: compilation/test failure because `WebSocketServer` has no `ChatService` constructor argument, persistence still goes through static `ChatUtils`, and the friends list injects `9999`.

- [ ] **Step 3: Implement the minimal decoupling**

Change the message path to the following order; a failed persistence must not be reported to the recipient as delivered:

```java
chatService.sendChat(userId, toId, content);
Session toSession = sessionRegistry.find(toId);
if (toSession != null && toSession.isOpen()) {
    ObjectNode push = objectMapper.createObjectNode();
    push.put("fromId", userId);
    push.put("content", content);
    push.put("type", "chat");
    toSession.getAsyncRemote().sendText(push.toString());
}
```

Delete the entire `toId == 9999L` branch, remove the mutual-follow bypass and `contactIds.add(9999L)`/special display block from `ChatServiceImpl`, and delete `ChatUtils.java`. Do not replace `new Thread` with another unbounded executor.

- [ ] **Step 4: Run green and no-AI integration tests**

Run: `./mvnw -Dtest=WebSocketServerTest,PrivateMessageIntegrationTest,WebSocketTicketEndpointIntegrationTest test`

Expected: PASS with real MySQL/Redis WebSocket persistence and zero AI bean/key requirement.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/websocket src/main/java/cumt/zongzuo/community/service/impl/ChatServiceImpl.java src/test/java/cumt/zongzuo/community/websocket
git commit -m "fix(chat): decouple private messaging from legacy ai"
```

## Task 2: Remove the legacy GET AI surface and monolithic service/tool client

**Files:**

- Delete: `src/main/java/cumt/zongzuo/community/controller/AiController.java`
- Delete: `src/main/java/cumt/zongzuo/community/service/MetroAiService.java`
- Delete: `src/main/java/cumt/zongzuo/community/config/AiToolConfig.java`
- Delete: `src/main/java/cumt/zongzuo/community/mq/ArticleAuditConsumer.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/LegacyAiSurfaceIntegrationTest.java`

**Interfaces:** none; this task intentionally removes `/api/ai/**`, `defaultFunctions("searchArticlesTool")`, free-text `PASS/REJECT`, and the shared ChatClient.

- [ ] **Step 1: Write the failing removal test**

With an authenticated JWT, assert both old GET routes return 404, and inspect the Spring context/source tree:

```java
assertThat(restTemplate.exchange(url("/api/ai/chat?msg=hello"), GET, bearer(userId), String.class)
    .getStatusCode().value()).isEqualTo(404);
assertThat(context.containsBean("metroAiService")).isFalse();
assertThat(context.containsBean("searchArticlesTool")).isFalse();
```

The dependency-removal assertion belongs to Task 3 so that every task ends with the complete selected test suite green.

- [ ] **Step 2: Verify red**

Run: `./mvnw -Dtest=LegacyAiSurfaceIntegrationTest test`

Expected: FAIL because `/api/ai/chat`, `metroAiService`, and `searchArticlesTool` still exist.

- [ ] **Step 3: Delete the prototype**

Delete the five files above and remove their imports/references. Keep `article.audit.queue` declared and keep `ArticleServiceImpl.publishOrSave(..., true, ...)` setting status `2`; this preserves the existing administrator pending list until Task 6 installs an explicit safe consumer. Do not add a replacement public AI endpoint.

- [ ] **Step 4: Verify the removed surface**

Run: `./mvnw -Dtest=LegacyAiSurfaceIntegrationTest test`

Expected: PASS; ordinary `/api/chat/**` and `/api/article/**` routes remain present.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/cumt/zongzuo/community/controller/AiController.java src/main/java/cumt/zongzuo/community/service/MetroAiService.java src/main/java/cumt/zongzuo/community/config/AiToolConfig.java src/main/java/cumt/zongzuo/community/mq/ArticleAuditConsumer.java src/test/java/cumt/zongzuo/community/ai
git commit -m "refactor(ai): remove legacy ai prototype endpoints"
```

## Task 3: Upgrade the platform and prove all-off/no-Key startup

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/cumt/zongzuo/community/IntegrationTestSupport.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/NoAiStartupIntegrationTest.java`
- Modify: `src/test/java/cumt/zongzuo/community/ai/LegacyAiSurfaceIntegrationTest.java`

**Interfaces:** dependency baseline only. MVC remains Servlet; WebFlux is used only for Provider streaming/client support.

- [ ] **Step 1: Write dependency/startup gates**

Assert `pom.xml` contains exact versions/artifacts and no milestone/openai legacy starter. In `NoAiStartupIntegrationTest`, use the existing real Testcontainers base, inject `ApplicationContext`, and assert:

```java
assertThat(context.getBeansOfType(DispatcherServlet.class)).hasSize(1);
assertThat(context.getBeanNamesForType(DeepSeekChatModel.class)).isEmpty();
assertThat(context.getBeanNamesForType(OllamaEmbeddingModel.class)).isEmpty();
assertThat(restTemplate.getForEntity(url("/actuator/health"), String.class).getStatusCode().is2xxSuccessful()).isTrue();
```

- [ ] **Step 2: Run red dependency/startup tests**

Run: `./mvnw -Dtest=NoAiStartupIntegrationTest,LegacyAiSurfaceIntegrationTest test`

Expected: FAIL because the exact versions/new dependencies/model-disable properties are absent.

- [ ] **Step 3: Upgrade `pom.xml` and disable model auto-configuration by default**

Use Boot `3.5.16`, Spring AI BOM `1.1.8`, MyBatis starter `3.5.17`; replace the old starter with `spring-ai-starter-model-deepseek` and `spring-ai-starter-model-ollama`; add WebFlux, Actuator, Prometheus registry and `resilience4j-spring-boot3`. Remove `<repositories>spring-milestones</repositories>`.

Set these non-secret defaults:

```yaml
spring:
  ai:
    model:
      chat: none
      embedding: none
    retry:
      max-attempts: 1
```

Replace every `spring.ai.openai.*` override in `IntegrationTestSupport` with `spring.ai.model.chat=none`, `spring.ai.model.embedding=none`, and empty business flags. Do not add a placeholder API key.

- [ ] **Step 4: Run green startup and dependency verification**

Run:

```bash
./mvnw -Dtest=NoAiStartupIntegrationTest,LegacyAiSurfaceIntegrationTest test
./mvnw dependency:tree -Dincludes=org.springframework.ai,com.baomidou:mybatis-plus-spring-boot3-starter
```

Expected: PASS; tree resolves Spring AI 1.1.8/MyBatis 3.5.17, with no M5 OpenAI starter; no outbound Provider connection occurs.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/java/cumt/zongzuo/community/IntegrationTestSupport.java src/test/java/cumt/zongzuo/community/ai
git commit -m "build(ai): upgrade spring ai foundation"
```

## Task 4: Add typed capability configuration and provider-neutral gateways

**Files:**

- Create: `src/main/java/cumt/zongzuo/community/ai/config/MetroAiProperties.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/config/AiProviderConfiguration.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/AiCapability.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/AiChatGateway.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/EmbeddingGateway.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/AiChatCommand.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/AiChatResult.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/EmbeddingCommand.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/EmbeddingResult.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/AiProviderException.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/DeepSeekAiChatGateway.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/OllamaEmbeddingGateway.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/DisabledAiChatGateway.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/provider/DisabledEmbeddingGateway.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/cumt/zongzuo/community/ai/config/MetroAiPropertiesTest.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/provider/DeepSeekAiChatGatewayContractTest.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/provider/OllamaEmbeddingGatewayContractTest.java`

**Interfaces:**

```java
public interface AiChatGateway {
    AiChatResult generate(AiChatCommand command);
}

public interface EmbeddingGateway {
    EmbeddingResult embed(EmbeddingCommand command);
}

public record AiChatCommand(AiCapability capability, List<AiPromptMessage> messages,
                            AiResponseMode responseMode) {}
public record AiChatResult(String text, String finishReason, long inputTokens,
                           long outputTokens, String provider, String model) {}
public record EmbeddingCommand(AiCapability capability, List<String> inputs) {}
public record EmbeddingResult(List<float[]> vectors, String provider, String model) {}
```

`AiCapability` values are `AGENT`, `ARTICLE_SUMMARY`, `WRITING`, `HYDE`, `MODERATION`, `MEMORY_EXTRACTION`, `EMBEDDING`. Spring AI `ChatModel`, `EmbeddingModel`, tool callbacks and Milvus types must not appear in these public signatures.

- [ ] **Step 1: Write typed-config and local-stub contract tests**

Bind a complete property set and assert defaults: all flags false, DeepSeek model `deepseek-v4-flash`, Ollama model `bge-m3`. Use JDK `HttpServer` on `127.0.0.1:0` to return deterministic DeepSeek `/chat/completions` and Ollama `/api/embed` JSON; assert parsed text/tokens and a 1024-float vector. A stub returning 429/500 increments an `AtomicInteger`; assert one gateway call produces exactly one HTTP request and a typed `AiProviderException` rather than a fallback string.

- [ ] **Step 2: Run red gateway tests**

Run: `./mvnw -Dtest=MetroAiPropertiesTest,DeepSeekAiChatGatewayContractTest,OllamaEmbeddingGatewayContractTest test`

Expected: FAIL because typed config and gateways do not exist.

- [ ] **Step 3: Implement config, adapters, availability fallback and retry-off**

Bind `metro.ai.enabled`, `agent.enabled`, `memory.enabled`, `writing.enabled`, `moderation.enabled`, `embedding.enabled`, provider base URL/key/model and per-capability limits. Construct `DeepSeekApi`/`DeepSeekChatModel` and `OllamaApi`/`OllamaEmbeddingModel` only when the root flag, relevant capability flag and credential/endpoint are present. Pass a Spring Retry `RetryTemplate` with `maxAttempts(1)` to the DeepSeek model. Register disabled gateways with `@ConditionalOnMissingBean`; they throw `AI_DISABLED` or `AI_UNAVAILABLE` without network I/O.

Map connect/timeout, 429, retryable 5xx, non-retryable 4xx, malformed response and empty response to typed provider error reasons. Never catch and return a user-facing sentence.

- [ ] **Step 4: Run green protocol and no-Key tests**

Run: `./mvnw -Dtest=MetroAiPropertiesTest,DeepSeekAiChatGatewayContractTest,OllamaEmbeddingGatewayContractTest,NoAiStartupIntegrationTest test`

Expected: PASS; stub counters prove no hidden retry, and empty keys still start with disabled gateways.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/ai/config src/main/java/cumt/zongzuo/community/ai/provider src/main/resources/application.yml src/test/java/cumt/zongzuo/community/ai
git commit -m "feat(ai): add typed capability provider gateways"
```

## Task 5: Add bounded execution, quotas, outer resilience and metrics

**Files:**

- Create: `src/main/java/cumt/zongzuo/community/ai/runtime/AiInvocationContext.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/runtime/AiCapabilityExecutor.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/runtime/AiQuotaService.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/runtime/RedisAiQuotaService.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/runtime/AiMetrics.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/runtime/AiExecutionException.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/config/AiRuntimeConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/cumt/zongzuo/community/ai/runtime/AiCapabilityExecutorTest.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/runtime/AiQuotaServiceIntegrationTest.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/runtime/AiMetricsTest.java`

**Interfaces:**

```java
public record AiInvocationContext(AiCapability capability, Long userId, String requestId,
                                  int inputCharacters, Instant deadline, boolean background) {}
public interface AiCapabilityExecutor {
    <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation);
}
public interface AiQuotaService {
    void acquire(AiInvocationContext context);
}
```

- [ ] **Step 1: Write red tests for hard limits and real Redis quota atomicity**

Cover: 4,001-character Agent input rejected before operation; expired deadline rejected; Agent ninth request in one minute and 101st in one day rejected; concurrent Redis acquisition cannot exceed eight; a full Agent bulkhead rejects immediately; 429/connect/selected 5xx retries once for interactive calls; validation/4xx/oversize never retries; background retry is capped at three; metrics have only `capability/provider/model/outcome` tags and never userId/request text.

- [ ] **Step 2: Run red runtime tests**

Run: `./mvnw -Dtest=AiCapabilityExecutorTest,AiQuotaServiceIntegrationTest,AiMetricsTest test`

Expected: FAIL because the runtime boundary does not exist.

- [ ] **Step 3: Implement bounded policies**

Use Redis Lua for minute/day quota increments plus TTL in one atomic operation. Configure bounded per-capability executors/bulkheads with initial concurrency `AGENT=8`, `MODERATION=2`, `MEMORY_EXTRACTION=2`, `EMBEDDING=4`; no `Executors.newCachedThreadPool`, raw `new Thread`, or unbounded queue. Enforce total timeouts `AGENT=45s`, `ARTICLE_SUMMARY=60s`, `WRITING=60s`, `HYDE=8s`, moderation block `20s`, moderation task `90s`. Apply Resilience4j retry/circuit breaker/time limiter outside the gateway and always cap work by `context.deadline()`.

Record `ai.request.count/latency/tokens`, `ai.quota.rejected`, `ai.bulkhead.rejected`, `ai.circuit.state`, `provider.timeout/429/5xx`. Redis quota failure must fail the AI admission, not unrelated endpoints.

- [ ] **Step 4: Run green tests and inspect threads/metrics**

Run: `./mvnw -Dtest=AiCapabilityExecutorTest,AiQuotaServiceIntegrationTest,AiMetricsTest test`

Expected: PASS; operation counters equal the allowed retry ceilings, queue-capacity tests reject deterministically, and meter tags contain no high-cardinality identity/content.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/ai/runtime src/main/java/cumt/zongzuo/community/ai/config src/main/resources/application.yml src/test/java/cumt/zongzuo/community/ai/runtime
git commit -m "feat(ai): enforce capability budgets and telemetry"
```

## Task 6: Route disabled or unavailable moderation to humans

**Files:**

- Create: `src/main/java/cumt/zongzuo/community/ai/moderation/ManualReviewRoutingService.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/moderation/LegacyModerationSubmissionConsumer.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/moderation/ModerationFallbackIntegrationTest.java`
- Modify: `src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java`

**Interfaces:**

```java
public interface ManualReviewRoutingService {
    void routeLegacyArticle(Long articleId, String reasonCode);
}
```

In stage A, `article.status=2 && is_deleted=0` is the existing human-pending state. No model result is accepted because immutable revisions and structured attempts do not exist until B.

- [ ] **Step 1: Write real-container fallback tests**

Insert articles in status 2, invoke the Rabbit consumer with AI off, missing Key and an unavailable local stub Provider configuration, then assert status remains 2, no ES sync/publish decision is emitted, the article remains visible to `/api/article/admin/pending`, and a human administrator can still approve through the existing admin API. Also assert deleted/non-pending articles are ignored idempotently.

- [ ] **Step 2: Run red fallback tests**

Run: `./mvnw -Dtest=ModerationFallbackIntegrationTest test`

Expected: FAIL because the legacy consumer was removed and no explicit fallback records/metrics the human route.

- [ ] **Step 3: Implement fail-closed legacy routing**

The consumer must call `routeLegacyArticle(articleId, "AI_FOUNDATION_MANUAL_ONLY")` and ACK only after it verifies the current row. The service never calls `auditArticle`, never changes status to 1/3, and records `moderation.pending.age`/fallback outcome. Database/transient consumer failure is rethrown for the existing bounded Rabbit retry/DLQ; even after DLQ, the source row remains status 2 and therefore visible to administrators.

- [ ] **Step 4: Run green fallback/manual tests**

Run: `./mvnw -Dtest=ModerationFallbackIntegrationTest,SecurityIntegrationTest test`

Expected: PASS; disabled/unavailable AI never publishes/rejects and manual review remains functional.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/ai/moderation src/test/java/cumt/zongzuo/community/ai/moderation src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java
git commit -m "fix(moderation): fail closed to manual review"
```

## Task 7: Scope RFC 9457 errors to new AI APIs and extend CORS

**Files:**

- Create: `src/main/java/cumt/zongzuo/community/ai/web/AiApi.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/web/AiApiException.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/web/AiProblemDetails.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/web/AiProblemDetailAdvice.java`
- Modify: `src/main/java/cumt/zongzuo/community/config/SecurityConfig.java`
- Do not modify response shapes in: `src/main/java/cumt/zongzuo/community/exception/GlobalExceptionHandler.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/web/AiProblemDetailIntegrationTest.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/web/AiCorsIntegrationTest.java`
- Modify: `src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java`

**Interfaces:** `@AiApi` marks only future `/api/agent/**` and `/api/admin/moderation/**` controllers. `AiProblemDetails.create(HttpStatus, code, requestId, retryable, retryAfterSeconds, fieldErrors)` returns `ProblemDetail` with `application/problem+json`.

- [ ] **Step 1: Write red contract tests**

Register a test-only `@AiApi` controller and assert malformed JSON/validation/disabled/quota errors return real 400/503/429 with fields `type,title,status,detail,instance,code,requestId,retryable,retryAfterSeconds,fieldErrors`. Assert unauthenticated `/api/agent/test` uses ProblemDetail, while malformed `/api/auth/login` and unauthorized `/api/message/unread` keep the existing `Result {code,msg,data}` body. Send OPTIONS with requested method PATCH and headers `Authorization,Last-Event-ID,Idempotency-Key,Content-Type`; assert all are allowed.

- [ ] **Step 2: Run red HTTP tests**

Run: `./mvnw -Dtest=AiProblemDetailIntegrationTest,AiCorsIntegrationTest,SecurityIntegrationTest test`

Expected: FAIL because CORS lacks PATCH/new headers and security/global errors always serialize `Result`.

- [ ] **Step 3: Implement path/annotation-scoped errors**

Give `AiProblemDetailAdvice` higher precedence but restrict it with `annotations = AiApi.class`. In Spring Security entry/access-denied handlers, select `AiProblemDetails` only when the path starts `/api/agent/` or `/api/admin/moderation/`; use the existing `writeError(Result)` for every legacy path. Map stable codes/statuses from the approved spec, set `Retry-After` for applicable 429/503, and never expose stack traces/provider bodies.

Change CORS to:

```java
configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
configuration.setAllowedHeaders(List.of(
    "Authorization", "token", "Content-Type", "Last-Event-ID", "Idempotency-Key"));
```

- [ ] **Step 4: Run green AI and legacy contract tests**

Run: `./mvnw -Dtest=AiProblemDetailIntegrationTest,AiCorsIntegrationTest,SecurityIntegrationTest test`

Expected: PASS; AI test routes use RFC 9457 and all existing endpoints retain `Result`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/ai/web src/main/java/cumt/zongzuo/community/config/SecurityConfig.java src/test/java/cumt/zongzuo/community/ai/web src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java
git commit -m "feat(ai-api): add scoped problem details and cors contract"
```

## Task 8: Document configuration, remove obsolete flags, and run the full gate

**Files:**

- Modify: `src/main/resources/application-example.yml`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `src/test/java/cumt/zongzuo/community/CommunityApplicationTests.java`

**Interfaces:** documented environment variables: `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `DEEPSEEK_MODEL=deepseek-v4-flash`, `OLLAMA_BASE_URL`, `OLLAMA_EMBEDDING_MODEL=bge-m3`; business switches are the six `metro.ai.*.enabled` properties and default false.

- [ ] **Step 1: Write failing documentation assertions**

Assert README and examples name the exact baselines/flags, state no-Key startup and manual-review fallback, state Provider services are not contacted by default, and do not mention `AI_CHAT_ENABLED`, old GET routes, robot 9999, M5 or the OpenAI starter.

- [ ] **Step 2: Run red documentation test**

Run: `./mvnw -Dtest=CommunityApplicationTests test`

Expected: FAIL because current docs still describe `AI_CHAT_ENABLED` and the old Provider setup.

- [ ] **Step 3: Update docs/examples without secrets**

Document how to keep all capabilities off and how an isolated non-production environment opts in. State explicitly: Stage A moderation is manual-only; revision binding/Outbox arrive in B; Ollama/Milvus runtime in C; Agent API/SSE in D. Do not add Milvus ports, migration SQL, Agent endpoints, or quality claims to Stage A configuration.

- [ ] **Step 4: Run focused and full verification**

Run:

```bash
./mvnw -Dtest=PrivateMessageIntegrationTest,NoAiStartupIntegrationTest,ModerationFallbackIntegrationTest test
./mvnw -Dtest=DeepSeekAiChatGatewayContractTest,OllamaEmbeddingGatewayContractTest,AiCapabilityExecutorTest,AiQuotaServiceIntegrationTest test
./mvnw -Dtest=AiProblemDetailIntegrationTest,AiCorsIntegrationTest,SecurityIntegrationTest test
./mvnw test
./mvnw -DskipTests package
git diff --check
rg -n "ChatUtils|MetroAiService|AiToolConfig|toId == 9999|spring-ai-openai-spring-boot-starter|1.0.0-M5|spring-milestones|AI_CHAT_ENABLED" src pom.xml README.md .env.example
```

Expected: every Maven command exits 0; the final `rg` exits 1 with no matches; all default integration tests use isolated Testcontainers and make zero live Provider calls.

- [ ] **Step 5: Commit**

```bash
git add README.md .env.example src/main/resources/application-example.yml src/test/java/cumt/zongzuo/community/CommunityApplicationTests.java
git commit -m "docs(ai): document disabled-by-default foundation"
```

## Stage A exit checklist

- [ ] Private message HTTP/WebSocket persistence works with every AI flag false and empty keys.
- [ ] No source or UI contract treats user `9999` as an AI conversation.
- [ ] Old GET `/api/ai/**`, `MetroAiService`, `AiToolConfig`, shared tool client and free-text AI audit are absent.
- [ ] Version/dependency/no-Key gates pass and the application remains Servlet/MVC.
- [ ] Gateway signatures contain no Spring AI/Milvus type and adapters prove one internal HTTP attempt using local stub servers.
- [ ] Capability limits, quotas, deadline, bounded queues, outer retries, error taxonomy and low-cardinality metrics have focused tests.
- [ ] AI-disabled/unavailable moderation stays in the human pending path and never calls publish/reject.
- [ ] New AI paths alone use ProblemDetail; old Result contracts and security tests still pass.
- [ ] Full Testcontainers suite and package build pass without live services.
- [ ] Git diff contains no revision/Outbox schema, Milvus code/Compose profile, Agent conversation/runtime/SSE, memory, or writing suggestion implementation.
