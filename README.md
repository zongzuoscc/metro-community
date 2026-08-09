# Metro Community

一个面向内容创作与互动的社区后端服务，提供文章、评论、点赞、收藏、关注、通知、全文检索和实时聊天能力。前端位于仓库的 `frontend` 分支。

## 技术栈

- Java 21、Spring Boot 3、Spring Security、MyBatis-Plus
- MySQL 8、Redis、RabbitMQ、Elasticsearch 8 + IK 中文分词
- WebSocket、阿里云 OSS、Spring AI（默认关闭，需显式配置）

## 已实现能力

- JWT 无状态认证：支持标准 `Authorization: Bearer <token>`，同时兼容现有前端的 `token` 请求头。
- 角色保护：文章、用户和举报管理接口需要管理员角色。
- 内容社区闭环：文章状态流转、评论、点赞、收藏、关注、系统通知与私信。
- Elasticsearch 全文检索、相似文章，以及 RabbitMQ 驱动的异步索引同步。
- Redis 缓存与接口限流。

## 本地启动

### 1. 准备依赖

需要 JDK 21、Docker（或已有的 MySQL 8、Redis、RabbitMQ、Elasticsearch 8）。Elasticsearch 必须安装仓库中 `elasticsearch/` Dockerfile 使用的 IK 插件。

项目 Docker Compose 使用独立默认端口：MySQL `13306`、Redis `16379`、RabbitMQ AMQP `15673` / 管理台 `15674`、Elasticsearch `19200`、Kibana `15601`，避免占用常见的本机开发端口。可以通过 `.env` 中的 `*_HOST_PORT` 覆盖它们。

```bash
docker compose up -d
```

### 2. 初始化数据库与环境变量

创建 `metro_community` 数据库并执行根目录的 [script.sql](script.sql)。随后复制环境变量示例并替换占位符；不要将真实密钥提交到 Git。通过 IDE 的运行环境、Shell 或部署平台将这些变量注入 Spring Boot 进程。已有数据库在启用推荐模型训练前必须先备份，并执行 [推荐训练迁移](docs/database/migrations/2026-08-09-recommendation-training.sql)；`script.sql` 仅用于新库初始化。

```bash
cp .env.example .env
```

至少需要设置：`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`、`REDIS_PASSWORD`、`RABBITMQ_PASSWORD`、`JWT_SECRET`、OSS 相关变量。`.env.example` 已按上述独立端口配置好后端连接地址。`JWT_SECRET` 必须至少 32 个字符。

在 Shell 中本地启动时，可显式加载该文件：

```bash
set -a && source .env && set +a
```

默认 CORS 仅允许 `http://localhost:5173`；生产环境请通过 `CORS_ALLOWED_ORIGINS` 设置前端正式域名。

推荐训练默认关闭。启用服务或调整真实曝光训练窗口时，可设置：`RECOMMENDATION_ENABLED`、`RECOMMENDATION_MODEL_WINDOW_DAYS`、`RECOMMENDATION_LABEL_WINDOW_DAYS`、`RECOMMENDATION_MODEL_MAX_AGE_DAYS`、`RECOMMENDATION_TRAINING_SAMPLE_LIMIT` 和 `RECOMMENDATION_MODEL_DIRECTORY`。模型目录应是应用进程可写的持久化绝对路径；迁移完成且训练任务已成功发布模型前，符合条件的用户仍会收到 chronology `COLD_START`。

RabbitMQ 工作队列现在配置了 3 次有限重试与死信队列（队列名后缀为 `.dlq`）。如果本地 RabbitMQ 已存在由旧版本创建的同名队列，需要先在管理界面删除这些**项目队列**后再启动，以便声明死信交换机参数；不要删除其他项目的队列。

### 3. 启动后端

```bash
./mvnw spring-boot:run
```

服务默认端口为 `8080`，可通过 `SERVER_PORT` 修改。

## AI 模块状态

当前没有可用的 AI Provider Key 时，聊天、向量、图片和音频模型均保持关闭，应用仍可正常启动。后续启用聊天模型需要设置 `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` 与 `AI_CHAT_ENABLED=true`。在重新接入前，请先完成 Provider 配置与集成测试，不应把 API Key 写入配置文件。

## 测试

```bash
./mvnw test
```

集成测试使用 Testcontainers 启动隔离的 MySQL、Redis、RabbitMQ 与带 IK 插件的 Elasticsearch，所有端口均由 Docker 动态分配，不会占用本机已有容器端口。
