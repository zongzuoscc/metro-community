# WebSocket 一次性 Ticket 设计

## 目标

消除长期 JWT 出现在 `/im/{credential}` URI 中的泄漏面，同时保留浏览器原生 WebSocket 无法自定义 `Authorization` 请求头时的连接能力。

## 安全边界

- `POST /api/ws/ticket` 由现有 Spring Security JWT 过滤链保护，未认证请求返回 HTTP 401。
- 签发的 ticket 由 `SecureRandom` 生成 32 字节随机数，用无填充 Base64URL 编码，不包含用户信息或 JWT。
- Redis 仅保存 `websocket:ticket:{ticket} -> userId`，默认 TTL 30 秒，由 `app.websocket.ticket-ttl` 配置。
- 签发使用带 TTL 的 `SET NX`，消费使用 Lua 将 `GET` 与 `DEL` 合并为一个原子操作。
- Redis 读写异常一律 fail-closed：HTTP 签发返回 503，WebSocket 连接用 1008 关闭。
- WebSocket 不再解析或接受 JWT。伪造、过期、重放和原 JWT 均不能建立可用会话。
- 日志不记录 ticket、JWT、WebSocket URI 或异常消息；只记录通用失败类型和已认证 userId。

## 组件与数据流

1. `WebSocketTicketController` 从 `CurrentUser` 获取 userId，调用 `WebSocketTicketService.issue` 并返回 ticket 及 TTL。
2. `WebSocketTicketService` 封装随机值生成、Redis 键格式、TTL 与原子消费，对外不暴露 Redis 异常细节。
3. `WebSocketConfig` 以 `ServerEndpointConfig` 程序化注册 `/im/{ticket}`。自定义 Configurator 每次通过 Spring `AutowireCapableBeanFactory.createBean` 创建 endpoint，避免 Jakarta WebSocket 容器直接实例化导致依赖注入失效。
4. `WebSocketServer.onOpen` 原子消费 ticket；成功后注册用户会话，失败则立即关闭。
5. `WebSocketSessionRegistry` 统一管理 `userId -> Session`。新连接取代旧连接，旧 endpoint 关闭时用 `remove(userId, oldSession)` 条件删除，不会误删新 session。

## 错误处理

- 无认证：HTTP 401，不写 Redis。
- Redis 签发故障：HTTP 503，返回通用文案。
- 非法格式、伪造、过期、重放、Redis 消费故障：WebSocket 1008 关闭。
- 同用户重连：新 session 成为唯一活跃映射，旧 session 正常关闭。

## 验证

- MockMvc 验证未认证 401 和认证签发。
- Testcontainers Redis 验证 ticket 仅可消费一次，过期后不可用。
- JDK WebSocket 客户端验证真实 endpoint 的成功连接，以及重放、伪造、过期和原 JWT 的 1008 关闭。
- Lettuce 连接不可用 Redis 验证签发与消费都 fail-closed。
- 会话注册表单测验证旧 session 的 close 不会移除新 session。
