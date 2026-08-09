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
- 已启动 Metro Community Java 后端及其依赖服务。后端默认监听 `http://localhost:8080`，启动方式与数据库、Redis、RabbitMQ、Elasticsearch 的环境变量说明见[后端 README](https://github.com/zongzuoscc/metro-community/blob/master/README.md)。

当前 `src/utils/request.js` 将 Axios 的 `baseURL` 设为 `http://localhost:8080`，所以浏览器会直接向该地址发起 API 请求。`vite.config.js` 同时保留了 `/api` 到同一地址的开发代理，供未来改用相对地址时使用；在现有实现中它不是必经路径。

后端默认只允许 `http://localhost:5173` 的跨域来源，因此前端开发服务器也使用该端口。若修改前端端口，需要同步设置后端 `CORS_ALLOWED_ORIGINS`。

## 本地启动

安装锁定版本的依赖：

```bash
npm ci
```

启动 Java 后端后，在此目录启动前端：

```bash
npm run dev -- --host 127.0.0.1
```

在浏览器打开 `http://localhost:5173`。即使 Vite 绑定在 `127.0.0.1`，也应使用 `localhost` 访问页面，以匹配后端默认的 `CORS_ALLOWED_ORIGINS=http://localhost:5173`。登录后，前端会从 `localStorage` 读取既有 `token` 并通过 `token` 请求头发送给后端。后端也兼容标准的 Bearer Token，但前端不会自行生成认证信息。

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
- 在真实用户浏览、互动和关注行为沉淀后，再接入可解释的个性化推荐排序；不以 mock 行为数据替代真实闭环。
