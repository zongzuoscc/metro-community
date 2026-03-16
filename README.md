# 🚇 Metro Community (Metro 智能化全栈开发者社区)

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)
![Vue 3](https://img.shields.io/badge/Vue.js-3.x-blue.svg)
![Elasticsearch](https://img.shields.io/badge/Elasticsearch-Search-yellow.svg)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-Message%20Queue-orange.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-Agentic%20RAG-purple.svg)

> **Metro Community** 是一个集高并发实时互动、海量全文检索与 Agent 智能体深度融合的现代化内容社区。本项目不仅实现了传统论坛的 CRUD 闭环，更着重解决了**分布式状态下的双写一致性**、**大模型异步风控**等企业级架构难题。

## ✨ 核心亮点与架构设计

* 🧠 **Agentic RAG 智能体引擎**：基于 Spring AI Function Calling 将私有 ES 检索封装为 AI 工具，赋予大模型自主调度知识库的精准问答能力；并在前端无缝集成长文“一键智能伴读摘要”。
* 🛡️ **分块熔断式 AI 异步内容风控**：自研“文本分块+快进熔断”算法拦截对抗性违规样本；基于 MQ 异步调度与状态分流闭环，将 AI 审核（耗时2-5s）与发帖主链路彻底解耦，发帖接口耗时降至 50ms 内。
* 🔄 **MQ 驱动的最终一致性架构**：以 MySQL 为单一真理源，引入 RabbitMQ 状态驱动增量同步链路。在复杂状态（草稿/审核/拦截/发布/删除）流转下，精准控制 Elasticsearch 索引的物理擦除与重建。
* ⚡ **高并发基建与检索算法**：基于 ES 的 BM25 算法实现高亮搜索，结合 `More Like This` 原生语法与 TF-IDF 实现“猜你喜欢”精准推荐；利用 Redis + 自定义 `@RateLimit` 注解实现轻量级 API 防刷限流器。
* 💬 **WebSocket 实时通信**：封装双向实时 P2P 聊天与系统通知模块，结合 JWT 实现了严密的握手鉴权与消息溯源。

## 🛠️ 技术栈

### 后端 (Backend)
- **核心框架**: Java 17 + Spring Boot 3
- **持久层**: MyBatis-Plus + MySQL 8.x
- **大模型基建**: Spring AI
- **缓存与限流**: Redis
- **消息队列**: RabbitMQ
- **搜索引擎**: Elasticsearch 8.x + IK 中文分词器
- **安全鉴权**: JWT

### 前端 (Frontend)
- **核心框架**: Vue 3 (Composition API) + Vite
- **UI 组件库**: Element Plus
- **Markdown**: `@kangc/v-md-editor` (支持沉浸式阅读与排版渲染)
- **网络请求**: Axios

---

## 🚀 快速开始 (Quick Start)

### 1. 环境准备 (Prerequisites)
在本地运行本项目，你需要提前安装以下环境：
* **JDK**: 17 或更高版本
* **Node.js**: v16+ (推荐 v18+)
* **MySQL**: 8.0+
* **Redis**: 6.0+
* **RabbitMQ**: 3.x (需开启 Web 管理插件)
* **Elasticsearch**: 包含对应版本的 `elasticsearch-analysis-ik` 插件
> 💡 **提示**: 强烈建议使用 Docker Compose 一键启动 MySQL、Redis、RabbitMQ 和 ES 等中间件服务。已将yml文件上传

### 2. 数据库初始化
1. 登录 MySQL，创建数据库 `metro_community`。
2. 运行项目根目录下的 SQL 脚本（如 `db/metro_community.sql`），初始化表结构与基础数据。

### 3. 后端服务配置与启动
1. 克隆本项目到本地：
   ```bash
   git clone [https://github.com/your-username/metro-community.git](https://github.com/your-username/metro-community.git)