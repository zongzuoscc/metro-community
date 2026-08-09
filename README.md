# Metro Community 前端

Metro Community 的 Vue 3 前端。它面向桌面端内容创作与社区浏览，采用暖纸张风格的统一视觉系统，并与同名 Java 后端保持现有 REST 协议兼容。

## 技术与范围

- Vue 3、Vite、Vue Router、Element Plus、Axios
- Tiptap 作为默认的所见即所得文章编辑器，支持标题、列表、引用、链接、图片、代码块和基础表格等常用写作能力。
- 文章正文在前端编辑后仍提交为 Markdown 兼容的 `String`，因此不需要改变现有后端文章字段或接口契约。
- `@tiptap/markdown` 当前仍是 Beta。基础 Markdown 能正常往返；复杂表格和未来的媒体节点不保证完全无损，发布前应检查文章内容。
- 页面调用真实后端接口，不包含演示数据或 mock 服务。

音频、视频、外部嵌入及富媒体持久化尚未默认启用。它们需要后端提供专用的媒体上传、转码或访问控制 API 后，再以 Tiptap 自定义节点接入，不能复用当前仅面向图片的上传接口。

## 前置条件

- Node.js：建议使用项目锁定依赖可支持的当前 LTS 版本。
- 已启动 Metro Community Java 21 后端及其依赖服务。项目的隔离本地配置让后端监听 `http://localhost:18080`，启动方式与 MySQL、Redis、RabbitMQ、Elasticsearch 的环境变量说明见[后端 README](https://github.com/zongzuoscc/metro-community/blob/master/README.md)。

Axios 从 `VITE_API_BASE_URL` 读取后端地址，默认值为 `http://localhost:18080`。WebSocket 连接前会用登录 JWT 调用 `POST /api/ws/ticket`，再从同一地址派生 `ws://localhost:18080/im/{ticket}`；JWT 不会出现在 WebSocket URI 中。HTTPS 环境会自动改用 `wss`，不再依赖硬编码的 8080 端口。`vite.config.js` 的开发代理由 `VITE_PROXY_TARGET` 控制。

前端隔离端口为 `15173`。后端启动时应设置 `CORS_ALLOWED_ORIGINS=http://localhost:15173,http://127.0.0.1:15173`；若修改前端端口，需要同步修改该配置。

## 本地启动

安装锁定版本的依赖：

```bash
npm ci
cp .env.example .env
```

启动 Java 后端后，在此目录启动前端：

```bash
npm run dev -- --host 127.0.0.1
```

在浏览器打开 `http://localhost:15173`。登录后，前端会从 `localStorage` 读取既有 `token`：普通 REST 请求继续使用现有 `token` 请求头，WebSocket 只在 ticket 签发请求的 `Authorization: Bearer` 中使用它。每次初始连接或断线重连都会申请新的一次性 ticket，网络或 HTTP 503 故障最多重试 5 次； ticket 请求返回 401 时立即清理登录态并跳转登录页，不会继续重试。退出登录、密码重置或账号切换会主动关闭连接，且已在途中的 ticket 响应不能重新打开旧账号连接。

## 首页推荐与最新流

- 已登录用户的“推荐”调用 `GET /api/recommendations/feed`，使用服务端返回的不透明游标；只有 `PERSONALIZED` 模式展示推荐理由。
- 访客的“推荐”和所有用户的“最新”都调用原有时间流接口。“最新”始终按发布时间展示，不受推荐模型影响。
- 推荐卡片进入详情页时携带服务端曝光 ID。详情页累计前台可见 8 秒后，最多上报一次 `POST /api/recommendations/views/{articleId}`；页面隐藏时间不计入阈值。
- 桌面导航和窄屏导航都保留推荐、最新、热榜、关注与搜索入口。窄屏文章编辑仍不在本轮范围内。

## 文章工作流

在 `/publish` 页面：

1. 使用 Tiptap 的工具栏、浮动格式菜单或常见 Markdown 快捷输入完成写作。
2. 自动保存和“保存草稿”调用 `POST /api/article/draft`，发布调用 `POST /api/article/publish`。
3. 封面和正文图片均通过 `POST /api/file/upload` 上传，编辑器只插入后端返回的可访问图片地址，不接受 `data:` 或 `blob:` 形式的地址。

这些请求均需要有效登录态。没有真实账号时，请不要通过伪造 token 或写入请求验证发布流程；可以正常运行前端、进行构建与页面静态检查。

## 校验与构建

```bash
npm run test -- --run
npm run build
```

`npm run build` 产物位于 `dist/`，可使用下列命令进行本地预览：

```bash
npm run preview
```

## 后续方向

- 在不改变现有文章接口稳定性的前提下，为音视频及嵌入内容设计专用媒体 API 与数据模型。
- 在真实流量和标签积累后评估更强的召回或排序模型，继续复用现有推荐接口和曝光事实，不以 mock 行为数据替代真实闭环。
