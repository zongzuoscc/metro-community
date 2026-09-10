# 社区混合检索：标准组件与业务策略

## 实际职责

检索仍采用 Elasticsearch BM25、Milvus Dense 和按需 HyDE，并由同一个社区文章工具提供给
原生 ReactAgent。没有再给每次模型调用安装自动检索 Advisor，因此不会因为两套入口重复检索。

| 组件 | 框架接口/实现 | 保留的社区职责 |
|---|---|---|
| `ArticleLexicalDocumentRetriever` | Spring AI `DocumentRetriever` | 调用既有 ES 关键词查询，使用 chunk ID 作为稳定文档 ID |
| `ArticleVectorDocumentRetriever` | Spring AI `DocumentRetriever` | 调用既有 Milvus SDK 适配，保留模型、解析代次和索引别名约束 |
| `HydeHypotheticalDocumentService` | Alibaba `HyDeTransformer` | 冻结模型路由、HYDE 配额、截止时间、取消检查、输出有效性 |
| `ReciprocalRankFusionDocumentJoiner` | Spring AI `DocumentJoiner` | 跨存储 RRF，保留各路排名和稳定的 chunk ID 排序 |
| `PublishedArticleDocumentPostProcessor` | Spring AI `DocumentPostProcessor` | 回 MySQL 校验最新公开版本，每篇最多两块，限制总上下文片段数 |

上述 Retriever、Joiner、PostProcessor 是项目针对官方扩展点的实现，不能描述为框架内置了
社区完整业务策略。HyDE 的通用生成则复用官方组件，不复制官方类或使用反射。
索引和原文依旧通过现有存储接口管理，本次没有将 SDK 仓储全部换成 `VectorStore`。

## 顺序与结果

1. 原问题分别进入 BM25 和 Dense；向量化继续使用已接入的官方 Embedding 模型。
2. 首轮结果经 RRF 融合、MySQL 校验和片段选择，得到真实可用候选。
3. 沿用现有短问题或有效候选不足条件，最多追加一次 HyDE。假设文档只进入第三路向量检索，
   不替换 BM25 的原问题，也不作为文章证据或历史回答保存。
4. 有第三路结果时重新融合与回源校验，再将标准 Document 转回既有 ArticleRetrievalResult。
5. 单个片段各路贡献为 `1 / (60 + rank)`，总分相同按 chunk ID 排序。
   MySQL 回源要求文章未删除、公开、chunk 活跃，且发布修订与解析代次匹配。

这里的 RRF 仍是跨 ES/Milvus 的应用层排名融合，不是 ES 服务端的混合查询。
官方 `HybridElasticsearchRetriever` 的关键词和向量查询都在 ES 内执行，不能直接替代当前存储布局。

## 运行边界

- 每请求的 Query、向量、模型路由和结果独立，不能保存在共享单例的可变请求字段中。
- 保持请求截止时间、配额、调用前后运行权及记忆代际检查；取消不能作为普通检索故障被吞掉。
- HyDE 空输出、截断或超长输出仍不接受，失败继续使用已经完成的首轮检索结果；取消例外，必须传播。
- 非空合法但事实不准确的假设文档仍可能影响召回质量，因此只允许用它找资料，不能把它当作事实。
- 保留三路排名和当前文章元数据，用于后续证据组装；凭据和完整路由对象不进入 Document metadata。
- 本轮不新增数据迁移、集合、索引、外部服务、用户配置开关或公开 API。

依赖采用 Spring AI 1.1.8 和 Alibaba RAG 1.1.2.0；只引用实际模块，排除不需要的
DashScope/ES 向量库 starter，避免新增自动配置改变原有连接和模型选择。

## 验证口径

检索单元测试覆盖三路融合、原问题保留、HyDE 条件、取消和冻结路由、无效输出、发布版本过滤
与单篇片段上限。独立 `HybridRetrievalLoadTest` 使用真实检索业务组件和确定性外部依赖替身，
对共享实例执行两批各 1000 个请求，检查排名、证据、模型路由和调用次数隔离。
运行命令与边界见 [检索组件并发回归](../scripts/agent-load/README.md#混合检索组件并发回归)。

该组件负载不等同于真实数据库或模型吞吐；也不是答案质量评测。
此次框架迁移不据此承诺召回率提高，需要后续带标注的问题集才能比较检索质量。
