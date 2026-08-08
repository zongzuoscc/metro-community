# Java 后端工程化加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Metro Community 后端升级为安全、可测试、可配置并可在 Java 17 上稳定构建的服务。

**Architecture:** Spring Security 的无状态 JWT 过滤器负责建立认证上下文。配置由 `@ConfigurationProperties` 和环境变量注入，接口用 DTO 和 Bean Validation 固化边界，RabbitMQ 使用有限重试和死信队列。测试使用 Testcontainers 启动真实 MySQL、Redis、RabbitMQ 和 Elasticsearch，并通过随机 HTTP 端口验证完整链路。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Security 6, JJWT, Jakarta Validation, Spring AMQP, Testcontainers, springdoc-openapi。

## Global Constraints

- `./mvnw test` 只使用 Java 17。它通过 Testcontainers 使用独立的 MySQL、Redis、RabbitMQ、Elasticsearch，不能连接用户已有的容器、OSS、邮箱或 AI 服务。
- Testcontainers 容器不得声明宿主机端口映射，全部使用 Docker 自动分配的临时端口；测试结束后自动清理。
- 生产配置、Docker 配置和文档不含真实凭据或硬编码 JWT 密钥。
- 兼容旧前端的 `token` 请求头，同时支持 `Authorization: Bearer`。
- 不改变现有公开文章、互动、聊天和管理端路径的业务语义。

**Test container contract:** 建立 `IntegrationTestSupport`，使用 `MySQLContainer("mysql:8.0")`、`GenericContainer("redis:7-alpine")`、`RabbitMQContainer("rabbitmq:3.13-management")` 和 `ElasticsearchContainer`。通过 `@DynamicPropertySource` 注入 JDBC、Redis、AMQP 和 ES 地址，HTTP 服务使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 及真实 `TestRestTemplate`，不使用 Mockito、MockMvc 或固定宿主机端口。

---

### Task 1: 恢复 Java 17 构建并移除敏感配置

**Files:**
- Modify: `pom.xml`, `src/main/resources/application.yml`, `.gitignore`
- Create: `src/main/resources/application-example.yml`, `.env.example`
- Modify: `src/test/java/cumt/zongzuo/community/CommunityApplicationTests.java`
- Create: `src/test/java/cumt/zongzuo/community/IntegrationTestSupport.java`
- Create: `src/test/resources/script.sql`

**Interfaces:**
- Produces: `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET`、`CORS_ALLOWED_ORIGINS` 的环境变量约定。

- [ ] **Step 1: 写失败测试**

```java
@Test
void targetsJava17AndKeepsProductionConfigSecretFree() throws IOException {
    assertThat(Files.readString(Path.of("pom.xml"))).contains("<java.version>17</java.version>");
    assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
        .doesNotContain("yangyiming.com");
}
```

- [ ] **Step 2: 确认失败**

Run: `./mvnw -Dtest=CommunityApplicationTests test`

Expected: FAIL，因为当前 POM 目标为 Java 21 且生产配置保留明文凭据。

- [ ] **Step 3: 最小实现**

将 POM 的 Java 版本设为 17，删除重复 Elasticsearch 依赖和 `com.alibaba:fastjson`，新增 `spring-boot-starter-validation`、`org.testcontainers:junit-jupiter`、`mysql`、`rabbitmq`、`elasticsearch` 和 `org.awaitility:awaitility`。所有数据库、Redis、RabbitMQ、邮箱、OSS、AI 和 JWT 配置只引用环境变量，示例配置使用占位值；忽略 `.env` 和 `application-local.yml`。将根目录 `script.sql` 复制到测试资源，让 MySQL 容器通过 `withInitScript("script.sql")` 建表。创建以下测试基类：

```java
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withInitScript("script.sql");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");
    @Container static final ElasticsearchContainer ES = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.4.1"))
        .withEnv("xpack.security.enabled", "false");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
        r.add("spring.elasticsearch.uris", ES::getHttpHostAddress);
    }
}
```

- [ ] **Step 4: 确认通过**

Run: `./mvnw -Dtest=CommunityApplicationTests test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pom.xml src/main/resources .env.example .gitignore src/test/java/cumt/zongzuo/community/CommunityApplicationTests.java
git commit -m "build: align backend with Java 17"
```

### Task 2: 建立 JWT 认证与统一角色授权

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/config/SecurityProperties.java`
- Create: `src/main/java/cumt/zongzuo/community/security/JwtService.java`, `JwtAuthenticationFilter.java`, `CurrentUser.java`
- Modify: `config/SecurityConfig.java`, `config/WebMvcConfig.java`, `utils/JwtUtils.java`
- Test: `src/test/java/cumt/zongzuo/community/security/JwtSecurityIntegrationTest.java`

**Interfaces:**
- Produces: `JwtService.generate(Long)`, `JwtService.parse(String)`, `CurrentUser.id()`, `ROLE_ADMIN`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void filterAuthenticatesBearerTokenAndAddsAdminRole() throws Exception {
    String token = loginAsSeededAdmin();
    ResponseEntity<String> response = restTemplate.exchange(
        url("/api/article/admin/pending"), HttpMethod.GET,
        new HttpEntity<>(headers("Bearer " + token)), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
}
```

- [ ] **Step 2: 确认失败**

Run: `./mvnw -Dtest=JwtSecurityIntegrationTest test`

Expected: FAIL，因为安全组件不存在。

- [ ] **Step 3: 最小实现**

```java
public final class CurrentUser {
    public static Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? null : (Long) auth.getPrincipal();
    }
}
```

过滤器先读 Bearer，再读旧 `token`，校验签名和有效期，读取用户状态并写入认证上下文。角色值 `1` 映射为 `ROLE_ADMIN`。配置使用无状态会话、明确开放的认证和公开 GET 路由，其余接口要求认证，管理端要求管理员。移除 MVC 登录拦截器注册，`JwtUtils` 只委托 `JwtService`。

- [ ] **Step 4: 确认通过**

Run: `./mvnw -Dtest=JwtSecurityIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cumt/zongzuo/community/{config,security,utils} src/test/java/cumt/zongzuo/community/security
git commit -m "feat: centralize JWT authentication"
```

### Task 3: 迁移受保护接口并明确管理端入参

**Files:**
- Create: `dto/admin/UpdateUserStatusDTO.java`, `AuditArticleDTO.java`, `ProcessReportDTO.java`
- Modify: `controller/UserController.java`, `ArticleController.java`, `ReportController.java`, `CommentController.java`, `FavoriteController.java`, `FollowController.java`, `LikeController.java`, `MessageController.java`, `ChatController.java`
- Modify: `service/impl/ArticleServiceImpl.java`
- Test: `src/test/java/cumt/zongzuo/community/controller/AuthorizationIntegrationTest.java`

**Interfaces:**
- Consumes: `CurrentUser.id()` 和 `@PreAuthorize("hasRole('ADMIN')")`。
- Produces: 不再由 controller 手动解析 token 的受保护接口。

- [ ] **Step 1: 写失败测试**

```java
ResponseEntity<String> forbidden = restTemplate.postForEntity(
    url("/api/article/admin/audit"), new HttpEntity<>("{\"articleId\":1,\"pass\":true}"), String.class);
assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

ResponseEntity<String> invalid = restTemplate.exchange(url("/api/user/admin/status"), HttpMethod.POST,
    new HttpEntity<>("{\"userId\":null,\"status\":7}", headers("Bearer " + loginAsSeededAdmin())), String.class);
assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
```

- [ ] **Step 2: 确认失败**

Run: `./mvnw -Dtest=AuthorizationIntegrationTest test`

Expected: FAIL，因为管理端还接受 Map 并且重复解析 token。

- [ ] **Step 3: 最小实现**

```java
public record AuditArticleDTO(
    @NotNull @Positive Long articleId,
    @NotNull Boolean pass,
    @Size(max = 255) String reason) {}
```

控制器加 `@Valid` 和 `@PreAuthorize`，从 `CurrentUser.id()` 获取身份。所有分页参数使用 `@Min(1)`、`@Max(100)`。文章详情可见性判断从认证上下文获取当前用户。

- [ ] **Step 4: 确认通过**

Run: `./mvnw -Dtest=AuthorizationIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cumt/zongzuo/community/{controller,dto,service/impl} src/test/java/cumt/zongzuo/community/controller
git commit -m "feat: validate protected community APIs"
```

### Task 4: 统一错误处理和上传策略

**Files:**
- Create: `exception/BusinessException.java`, `service/FileStorageService.java`
- Modify: `exception/GlobalExceptionHandler.java`, `controller/FileController.java`, `utils/OssUtils.java`
- Modify: `dto/LoginDTO.java`, `RegisterDTO.java`, `ResetPasswordDTO.java`, `UpdatePasswordDTO.java`, `ArticleDTO.java`, `CommentDTO.java`
- Test: `src/test/java/cumt/zongzuo/community/controller/FileUploadIntegrationTest.java`

**Interfaces:**
- Produces: `FileStorageService.upload(MultipartFile)`，仅允许 JPEG、PNG、WebP，最大 10 MB。

- [ ] **Step 1: 写失败测试**

```java
@Test
void rejectsExecutableDisguisedAsImage() {
    HttpHeaders headers = headers("Bearer " + loginAsSeededUser());
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    MultiValueMap<String, Object> body = multipart("avatar.jpg", "application/x-msdownload", new byte[] {1});
    ResponseEntity<String> response = restTemplate.postForEntity(url("/api/file/upload"), new HttpEntity<>(body, headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}
```

- [ ] **Step 2: 确认失败**

Run: `./mvnw -Dtest=FileUploadIntegrationTest test`

Expected: FAIL，因为文件直接交给 OSS，校验异常没有稳定的 400 响应。

- [ ] **Step 3: 最小实现**

```java
private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

public String upload(MultipartFile file) throws IOException {
    if (file == null || file.isEmpty() || file.getSize() > 10 * 1024 * 1024
        || !ALLOWED_TYPES.contains(file.getContentType())) {
        throw new BusinessException(400, "仅支持 10MB 以内的 JPG、PNG、WebP 图片");
    }
    return ossUtils.uploadImage(file);
}
```

DTO 使用 Jakarta Validation。全局异常处理器返回 400、401、403、404、409 和不泄露细节的 500，内部错误只记日志。

- [ ] **Step 4: 确认通过**

Run: `./mvnw -Dtest=FileUploadIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cumt/zongzuo/community/{controller,dto,exception,service,utils} src/test/java/cumt/zongzuo/community/controller
git commit -m "feat: validate input and secure file uploads"
```

### Task 5: 增加 RabbitMQ 重试和死信队列

**Files:**
- Modify: `config/RabbitConfig.java`, `application.yml`
- Modify: `mq/ArticleAuditConsumer.java`, `CommentConsumer.java`, `EsSyncConsumer.java`, `LikeConsumer.java`, `MailConsumer.java`, `NotificationConsumer.java`
- Test: `src/test/java/cumt/zongzuo/community/mq/RabbitDeadLetterIntegrationTest.java`

**Interfaces:**
- Produces: `community.dlx`，每个业务队列的死信 routing key，以及三次有限重试。

- [ ] **Step 1: 写失败测试**

```java
@Test
void auditQueueDeclaresDeadLetterExchange() {
    rabbitTemplate.convertAndSend("article.audit.queue", -1L);
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
        assertThat(deadLetterMessageCount("article.audit.dlq")).isEqualTo(1));
}
```

- [ ] **Step 2: 确认失败**

Run: `./mvnw -Dtest=RabbitDeadLetterIntegrationTest test`

Expected: FAIL，因为现有队列没有死信参数。

- [ ] **Step 3: 最小实现**

```java
factory.setAdviceChain(RetryInterceptorBuilder.stateless()
    .maxAttempts(3).backOffOptions(1000, 2.0, 10000)
    .recoverer(new RejectAndDontRequeueRecoverer()).build());
```

每个业务队列声明死信 exchange 和队列专属 routing key。删除消费者中的 `printStackTrace()` 和吞异常分支，记录队列、业务 ID、异常并重新抛出。不要在此任务引入 Outbox 或改动消息负载。

- [ ] **Step 4: 确认通过**

Run: `./mvnw -Dtest=RabbitDeadLetterIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/cumt/zongzuo/community/{config,mq} src/main/resources/application.yml src/test/java/cumt/zongzuo/community/config
git commit -m "feat: route failed MQ tasks to dead letter queues"
```

### Task 6: 完成 OpenAPI、CI、隔离 Docker 配置和 README

**Files:**
- Modify: `pom.xml`, `docker-compose.yml`, `README.md`
- Create: `config/OpenApiConfig.java`, `.github/workflows/backend-ci.yml`
- Test: `src/test/java/cumt/zongzuo/community/config/OpenApiIntegrationTest.java`

**Interfaces:**
- Produces: `/swagger-ui/index.html`、Java 17 CI 和不自动启动的隔离 Compose 配置。

- [ ] **Step 1: 写失败测试**

```java
@Test
void exposesMetroCommunityMetadata() {
    ResponseEntity<String> response = restTemplate.getForEntity(url("/v3/api-docs"), String.class);
    assertThat(response.getBody()).contains("Metro Community API");
}
```

- [ ] **Step 2: 确认失败**

Run: `./mvnw -Dtest=OpenApiIntegrationTest test`

Expected: FAIL，因为 OpenAPI 配置不存在。

- [ ] **Step 3: 最小实现**

```java
@Bean
OpenAPI metroOpenApi() {
    return new OpenAPI().info(new Info().title("Metro Community API").version("v1"));
}
```

添加 springdoc 依赖。Compose 顶层设置 `name: metro-community-dev`，删除固定 `container_name`，端口绑定 `127.0.0.1`，密码使用必填环境变量。CI 使用 Temurin 17 运行 `./mvnw --batch-mode test`。README 写明 Java 17、变量文件、隔离 Compose 启动、SQL 初始化、Swagger 地址、真实功能边界，删除无基准性能和完全一致性断言。

- [ ] **Step 4: 确认通过**

Run: `./mvnw test && docker compose -f docker-compose.yml config >/dev/null`

Expected: Maven PASS，Compose 只完成配置校验且不会启动容器。

- [ ] **Step 5: 提交**

```bash
git add pom.xml src/main/java/cumt/zongzuo/community/config .github/workflows docker-compose.yml README.md src/test/java/cumt/zongzuo/community/config
git commit -m "docs: document secure Java backend setup"
```

### Task 7: 全量验证和安全回归

- [ ] **Step 1: 执行全量测试**

Run: `./mvnw test`

Expected: PASS，输出不含 Java 版本错误、重复依赖警告或中间件连接失败。

- [ ] **Step 2: 执行敏感信息回归扫描**

Run: `rg -n -i 'password:\\s*[^$#[:space:]]|jwt-secret:\\s*[^$#[:space:]]|access-key-secret:\\s*[^$#[:space:]]' --glob '!target/**' .`

Expected: 不匹配真实值。

- [ ] **Step 3: 确认未操作 Docker**

Run: `docker compose -f docker-compose.yml ps`

Expected: 当前项目未创建或启动容器。

## Plan Self-Review

- Spec coverage: 安全、配置、DTO 校验、上传、MQ 失败处理、测试、OpenAPI、CI、Compose、README 都有对应任务。Outbox 与 AI 改造明确不在此计划内。
- Placeholder scan: 没有 TBD、TODO 或未定义实现。
- Type consistency: `SecurityProperties`、`JwtService`、`CurrentUser`、管理 DTO 与 `FileStorageService` 在各任务中的名称一致。
