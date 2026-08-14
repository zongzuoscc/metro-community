-- 本脚本只用于本地开发环境：为站内检索准备一组原创、可重复执行的已发布文章。
-- 它不会删除或覆盖已有文章；相同作者和标题已经存在时会复用原记录。
SET NAMES utf8mb4;
START TRANSACTION;

CREATE TEMPORARY TABLE seed_demo_article (
    seed_key       VARCHAR(40)  NOT NULL PRIMARY KEY,
    title          VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    summary_text   VARCHAR(255) NOT NULL,
    body_markdown  MEDIUMTEXT   NOT NULL,
    body_plain     MEDIUMTEXT   NOT NULL,
    tags_json      VARCHAR(500) NOT NULL,
    published_at   DATETIME     NOT NULL,
    content_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
);

INSERT INTO seed_demo_article
    (seed_key,title,summary_text,body_markdown,body_plain,tags_json,published_at)
VALUES
('java-transaction',
 'Spring 事务为什么会失效：从代理边界到可靠提交',
 '用几个常见场景解释 Spring 事务代理、自调用、异常传播和外部调用边界。',
 '## 事务不是给方法加一个标签\n\n很多开发者第一次使用 Spring 事务时，会把 `@Transactional` 理解成“进入方法就自动开启事务”。更准确的说法是：Spring 通常通过代理对象拦截方法调用，在代理边界开始、提交或回滚事务。如果同一个类中的方法直接调用另一个带事务注解的方法，这次调用没有经过代理，新的事务规则就可能没有生效。\n\n## 三个高频误区\n\n第一，自调用绕过代理。可以把需要独立事务的方法拆到另一个职责清晰的 Service。第二，只捕获异常但不重新抛出，外层事务会认为业务正常完成。第三，在数据库事务中调用耗时的远程接口，会长时间占用连接和行锁。\n\n## 更可靠的边界\n\n先在短事务中写入业务事实和待投递事件，再由后台任务处理消息、搜索索引或通知。外部调用失败时重试事件，而不是回滚已经确认的业务事实。对于并发修改，还应在更新语句中携带版本号，并检查受影响行数，避免后到请求覆盖先到请求。\n\n事务设计的目标不是让所有操作看起来同时发生，而是明确哪些事实必须原子提交，哪些副作用可以异步收敛。',
 '事务不是给方法加一个标签。很多开发者第一次使用 Spring 事务时，会把 Transactional 理解成进入方法就自动开启事务。更准确的说法是 Spring 通常通过代理对象拦截方法调用，在代理边界开始、提交或回滚事务。如果同一个类中的方法直接调用另一个带事务注解的方法，这次调用没有经过代理，新的事务规则就可能没有生效。三个高频误区包括自调用绕过代理、捕获异常后不重新抛出、在数据库事务中调用耗时远程接口。更可靠的做法是在短事务中写入业务事实和待投递事件，再由后台任务处理消息、搜索索引或通知。并发修改还应携带版本号并检查受影响行数。事务设计的目标是明确哪些事实必须原子提交，哪些副作用可以异步收敛。',
 '["Java","Spring","后端开发","事务"]','2026-08-07 09:20:00'),

('agent-rag',
 '从关键词到 HyDE：让社区 Agent 更懂长文章',
 '介绍关键词、向量检索、RRF 融合与 HyDE 如何共同改善短问题检索长文章。',
 '## 为什么短问题难以匹配长文章\n\n用户可能只问“事务为什么失效”，而文章正文会使用代理、自调用、异常传播等更完整的术语。只依赖关键词时，问题与文章之间的表达差异会导致漏检；只依赖向量时，又可能错过类名、配置项和错误码这样的精确信息。\n\n## 混合检索的分工\n\n关键词检索擅长精确名称，Dense 向量检索擅长同义表达。系统可以分别取得两组候选，再使用 RRF 根据排名进行融合，而不是直接比较两种搜索引擎不可比的原始分数。随后还要回到 MySQL 验证文章是否仍公开、修订是否仍是当前版本。\n\n## HyDE 的作用\n\n对于特别短的问题，可以先让模型生成一段“假设答案文档”，再对这段更接近文章表达方式的文本生成向量。它不是最终答案，也不能作为事实来源，只用于扩大召回。若生成失败，系统应继续使用关键词和普通向量结果。\n\n真正可靠的 Agent 不只追求搜到更多内容，还要标明信息来自站内文章、历史记忆还是联网来源，并在任何检索服务不可用时给出诚实的降级结果。',
 '为什么短问题难以匹配长文章。用户可能只问事务为什么失效，而文章正文会使用代理、自调用、异常传播等更完整的术语。只依赖关键词容易漏检，只依赖向量又可能错过类名、配置项和错误码。关键词检索擅长精确名称，Dense 向量检索擅长同义表达，可以分别取得候选后使用 RRF 按排名融合，再回到 MySQL 验证文章是否仍公开。对于特别短的问题，HyDE 可以先生成假设答案文档并向量化，它只用于扩大召回，不是事实来源。可靠的 Agent 还要标明站内文章、历史记忆和联网来源，并在检索服务不可用时诚实降级。',
 '["Agent","HyDE","RAG","人工智能"]','2026-08-08 10:00:00'),

('mysql-index',
 'MySQL 索引设计：不要只看有没有命中索引',
 '从联合索引顺序、扫描行数、覆盖索引和分页方式理解查询性能。',
 '## 命中索引不等于查询高效\n\n执行计划显示使用了某个索引，只能说明优化器选择了这条访问路径。真正需要观察的是扫描了多少行、过滤比例如何、是否发生回表，以及排序和临时表的成本。一个选择性很低的状态字段即使命中索引，也可能扫描大量记录。\n\n## 联合索引从查询约束出发\n\n设计联合索引时，应先确定等值条件、范围条件和排序字段。常见顺序是把稳定的等值条件放在前面，再放范围或时间字段，最后放用于稳定排序的主键。索引顺序应服务真实查询，而不是把所有字段机械地堆在一起。\n\n## 大数据量分页\n\n`LIMIT offset,size` 在页码很深时仍要跳过大量记录。更稳定的方式是记录上一页最后一条的时间和主键，使用键集分页继续查询。删除和归档任务也应采用小批次、稳定游标，并在真正删除时重新检查状态条件。\n\n性能优化的完整流程是先定义业务查询，再采集执行计划和真实数据分布，最后用可重复的压测验证，而不是凭经验增加索引。',
 '命中索引不等于查询高效。执行计划显示使用某个索引，只说明优化器选择了这条访问路径，还需要观察扫描行数、过滤比例、回表、排序和临时表成本。联合索引应从真实查询约束出发，通常先放稳定等值条件，再放范围或时间字段，最后放稳定排序主键。大数据量下深分页会跳过大量记录，更适合使用时间和主键组成的键集游标。删除和归档也应采用小批次并在删除时重新检查状态。性能优化应从业务查询、执行计划和真实数据分布出发，再用可重复压测验证。',
 '["MySQL","后端开发","性能优化","数据库"]','2026-08-09 14:30:00'),

('daily-energy',
 '精力管理比时间管理更重要：建立可持续的一天',
 '用任务分层、休息节奏和低摩擦习惯减少忙碌带来的消耗。',
 '## 先识别精力曲线\n\n每个人在一天中的专注程度并不恒定。与其把所有小时视为同样宝贵，不如连续记录一周，观察自己在哪些时段适合深度工作，哪些时段只适合回复消息、整理资料或散步。把最困难的任务放到精力高峰，通常比不断延长工作时间更有效。\n\n## 一天只保留一个核心结果\n\n长清单会制造完成了很多小事的错觉。每天先确定一个真正推动目标的结果，再安排两到三个维护性任务。临时请求出现时，先判断它是否值得替换当天核心结果，而不是无条件追加。\n\n## 让休息成为计划的一部分\n\n休息不是工作失败后的补偿。短暂离开屏幕、补水、晒太阳和稳定睡眠都会影响第二天的判断力。对于长期学习或求职，能够连续执行六周的节奏，比某一天坚持到凌晨更有价值。\n\n可持续的生活不是把每分钟都填满，而是让重要事情在身体和情绪都能承受的节奏下持续发生。',
 '先识别精力曲线。每个人一天中的专注程度并不恒定，可以连续记录一周，观察哪些时段适合深度工作，哪些时段适合回复消息和整理资料。一天只保留一个核心结果，再安排少量维护任务。临时请求出现时，判断它是否值得替换核心结果。休息不是失败后的补偿，离开屏幕、补水、晒太阳和稳定睡眠都会影响判断力。对于长期学习或求职，能够连续执行六周的节奏，比某一天熬到凌晨更有价值。可持续生活不是把每分钟填满，而是让重要事情以身体和情绪能承受的节奏持续发生。',
 '["习惯","健康生活","时间管理","生活方式"]','2026-08-10 08:10:00'),

('career-project',
 '准备技术面试时，怎样把项目讲成一个完整故事',
 '从问题、约束、方案、验证和结果五个部分组织项目表达。',
 '## 不要从技术名词开始\n\n面试官更关心你解决了什么问题。介绍项目时可以先说明用户是谁、原来的体验哪里不好，以及这个问题为什么值得投入。随后再解释数据规模、交付时间、兼容性或成本等约束。\n\n## 方案必须包含取舍\n\n只说“使用了 Redis、RabbitMQ 和 Elasticsearch”不能体现设计能力。更有效的表达是说明为什么同步方案会阻塞请求，为什么最终选择异步事件，以及系统如何处理重复投递、失败重试和数据一致性。没有选择理由的技术栈只是名词列表。\n\n## 用验证证明结果\n\n项目结果不一定非要是百万用户。可以说明关键链路有多少测试、并发冲突如何处理、服务如何降级、真实模型是否做过冒烟验证，以及 CI 如何阻止错误提交。数字必须来自实际证据，不要为了显得厉害而夸大。\n\n最后准备一两个失败案例：最初方案哪里不可靠，你如何复现问题并修改设计。能够清楚讲出边界和未完成项，通常比宣称系统完美更可信。',
 '技术面试中不要从技术名词开始，应先说明用户、原有问题和投入价值，再解释数据规模、交付时间、兼容性和成本约束。方案必须包含取舍，只罗列 Redis、RabbitMQ 和 Elasticsearch 不能体现设计能力，应说明为何选择异步事件以及如何处理重复投递、失败重试和一致性。项目结果可以使用测试数量、并发处理、降级路径、真实模型冒烟和 CI 结果证明，数字必须来自实际证据。还应准备失败案例，说明如何复现问题和修正设计。清楚表达边界和未完成项比宣称系统完美更可信。',
 '["后端开发","技术面试","求职","项目经验"]','2026-08-11 19:40:00'),

('news-literacy',
 '信息爆炸时代的新闻阅读：先核实，再转发',
 '提供一套识别标题党、断章取义和未经证实消息的阅读方法。',
 '## 标题不是事实本身\n\n社交平台上的标题往往为了吸引点击而压缩背景，甚至把可能、研究中或个别案例写成已经确定的普遍结论。看到情绪强烈的标题时，第一步不是站队，而是打开原文确认发布机构、时间、适用范围和原始表述。\n\n## 寻找能够相互校验的来源\n\n同一消息最好至少找到两个相互独立的可靠来源。如果多篇文章都引用同一个未署名截图，它们并不构成多来源证据。涉及政策、公共安全和健康的信息，应优先查看主管部门、正式公告或原始研究。\n\n## 区分事实、解释和预测\n\n事实可以被核实，解释包含作者的因果判断，预测则依赖尚未发生的条件。阅读时把三者分开，可以减少把观点当成新闻的风险。还要注意旧闻重新传播、图片脱离原场景和统计口径变化。\n\n联网搜索能够帮助找到更多材料，但搜索结果数量不能替代来源质量。负责任的阅读习惯，是在信息不足时保留不确定性，而不是急于给出确定结论。',
 '标题不是事实本身。社交平台标题可能压缩背景，把可能或个别案例写成普遍结论。看到情绪强烈的标题，应确认发布机构、时间、范围和原始表述。同一消息最好找到两个相互独立的可靠来源，多篇文章引用同一张未署名截图不算多来源。涉及政策、公共安全和健康，应优先查看正式公告或原始研究。阅读时区分可核实事实、作者解释和未来预测，并注意旧闻重传、图片脱离场景和统计口径变化。联网搜索能提供材料，但结果数量不能代替来源质量；信息不足时应保留不确定性。',
 '["信息素养","媒体","新闻","网络安全"]','2026-08-12 12:15:00'),

('green-community',
 '从一件小事开始：社区低碳生活实践指南',
 '把节能、减废、共享和公共参与转化为可持续的社区行动。',
 '## 先减少不必要的消耗\n\n低碳生活不等于购买更多带有环保标签的商品。更直接的起点是延长已有物品的使用时间，减少一次性用品，并在购买前确认是否真的需要。家中可以从照明、空调温度和待机设备入手，记录一个月的用电变化。\n\n## 共享比闲置更有效\n\n电钻、梯子、露营装备和专业书籍使用频率不高，却占用空间。社区可以建立小规模共享清单，明确借用期限、损坏责任和清洁要求。共享机制的关键不是规模，而是规则简单、物品可追踪。\n\n## 让行动形成反馈\n\n如果只有口号，参与热情很快会消失。可以公开每月旧物交换数量、减少的一次性用品和公共空间改善情况，同时避免把个人排名变成压力。遇到垃圾分类、噪声或公共绿地问题时，应通过物业、社区组织和公开议事渠道解决。\n\n真正可持续的社区行动需要方便、透明和能够被普通居民长期坚持。',
 '低碳生活不等于购买更多环保商品，更直接的起点是延长物品使用时间、减少一次性用品，并从照明、空调和待机设备记录用电变化。电钻、梯子、露营装备和专业书籍使用频率不高，可以通过规则简单、物品可追踪的社区共享机制减少闲置。行动还需要反馈，可以公开旧物交换数量、减少的一次性用品和公共空间改善情况。垃圾分类、噪声和公共绿地问题应通过物业、社区组织和公开议事渠道解决。可持续社区行动需要方便、透明，并能被普通居民长期坚持。',
 '["低碳","公共参与","社区","生活方式"]','2026-08-13 16:25:00'),

('city-walk',
 '慢一点旅行：用城市漫步重新认识一座城',
 '不追求打卡数量，通过路线、观察和记录获得更有层次的旅行体验。',
 '## 给路线留下空白\n\n城市旅行不必把所有热门地点塞进一天。选择一个交通节点、一段老街和一个公共空间作为骨架，中间预留可以随时停下的时间。真正让人记住一座城市的，往往是市场里的声音、树荫下的座椅和普通居民使用空间的方式。\n\n## 带着问题观察\n\n可以关注街道是否方便步行，老建筑如何继续使用，小店与连锁商业怎样共存，河流和公园是否真正连接社区。旅行因此不只是消费景点，也成为理解城市生活的一次田野观察。\n\n## 尊重当地日常\n\n拍摄人物前先征得同意，不占用居民通道，不把安静社区变成高声直播背景。品尝地方食物时可以询问做法和来源，而不是只寻找网络评分最高的店。\n\n回程后整理一张手绘路线、几段文字和少量照片，比保存几百张相似画面更容易形成记忆。慢旅行的价值不在于去得更远，而在于看得更仔细。',
 '城市旅行不必把所有热门地点塞进一天。选择交通节点、老街和公共空间作为路线骨架，中间保留停留时间。可以观察街道是否方便步行、老建筑如何继续使用、小店与连锁商业如何共存、河流和公园是否连接社区。旅行不只是消费景点，也是理解城市生活的观察。拍摄人物前应征得同意，不占居民通道，不把安静社区变成直播背景。回程后整理手绘路线、文字和少量照片，比保存几百张相似画面更容易形成记忆。慢旅行的价值不在去得更远，而在看得更仔细。',
 '["城市观察","旅行","生活方式","记录"]','2026-08-14 09:00:00');

-- 内容摘要算法与 ArticleContentCanonicalizer 保持一致，确保后续修订校验和分块回填可通过。
UPDATE seed_demo_article
SET content_hash=SHA2(CONCAT(
        'article-content-v1\n',
        OCTET_LENGTH(title),':',title,'\n',
        OCTET_LENGTH(summary_text),':',summary_text,'\n',
        OCTET_LENGTH(body_markdown),':',body_markdown,'\n',
        '0:','\n',
        OCTET_LENGTH(tags_json),':',tags_json),256);

INSERT INTO article
    (title,summary,content,author_id,view_count,like_count,create_time,update_time,status,
     cover,is_deleted,comment_count,collect_count,visibility_state,review_state,
     lifecycle_epoch,lock_version)
SELECT s.title,s.summary_text,s.body_markdown,1,0,0,s.published_at,s.published_at,1,
       '',0,0,0,'PUBLIC','APPROVED',1,0
FROM seed_demo_article s
WHERE NOT EXISTS (
    SELECT 1 FROM article a WHERE a.author_id=1 AND a.title=s.title
);

INSERT INTO article_revision
    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
     content_hash,source_draft_version,created_by,created_at)
SELECT a.id,1,s.title,s.summary_text,s.body_markdown,s.body_plain,'',CAST(s.tags_json AS JSON),
       s.content_hash,1,1,s.published_at
FROM seed_demo_article s
JOIN article a ON a.author_id=1 AND a.title=s.title
WHERE NOT EXISTS (
    SELECT 1 FROM article_revision r WHERE r.article_id=a.id AND r.revision_no=1
);

INSERT INTO article_draft
    (article_id,user_id,draft_version,title,summary,body_markdown,body_plain,cover,tags_json,
     content_hash,created_at,updated_at,lock_version)
SELECT a.id,1,1,s.title,s.summary_text,s.body_markdown,s.body_plain,'',CAST(s.tags_json AS JSON),
       s.content_hash,s.published_at,s.published_at,0
FROM seed_demo_article s
JOIN article a ON a.author_id=1 AND a.title=s.title
WHERE NOT EXISTS (SELECT 1 FROM article_draft d WHERE d.article_id=a.id);

INSERT INTO article_moderation_job
    (article_id,revision_id,content_hash,state,attempt_count,reviewer_id,review_reason,
     reviewed_at,created_at,updated_at,lock_version)
SELECT a.id,r.id,r.content_hash,'HUMAN_APPROVED',0,1,'本地演示文章初始化',
       s.published_at,s.published_at,s.published_at,1
FROM seed_demo_article s
JOIN article a ON a.author_id=1 AND a.title=s.title
JOIN article_revision r ON r.article_id=a.id AND r.revision_no=1
WHERE NOT EXISTS (
    SELECT 1 FROM article_moderation_job j
    WHERE j.article_id=a.id AND j.revision_id=r.id
);

UPDATE article a
JOIN seed_demo_article s ON a.author_id=1 AND a.title=s.title
JOIN article_revision r ON r.article_id=a.id AND r.revision_no=1
SET a.latest_revision_id=r.id,
    a.pending_revision_id=NULL,
    a.published_revision_id=r.id,
    a.visibility_state='PUBLIC',
    a.review_state='APPROVED',
    a.status=1,
    a.is_deleted=0,
    a.lock_version=1,
    a.update_time=s.published_at;

INSERT IGNORE INTO tag(name,article_count,create_time)
SELECT DISTINCT parsed.name,0,CURRENT_TIMESTAMP
FROM seed_demo_article s
CROSS JOIN JSON_TABLE(s.tags_json,'$[*]' COLUMNS(name VARCHAR(50) PATH '$')) parsed;

INSERT IGNORE INTO article_tag(article_id,tag_id)
SELECT a.id,t.id
FROM seed_demo_article s
JOIN article a ON a.author_id=1 AND a.title=s.title
CROSS JOIN JSON_TABLE(s.tags_json,'$[*]' COLUMNS(name VARCHAR(50) PATH '$')) parsed
JOIN tag t ON t.name=parsed.name COLLATE utf8mb4_0900_bin;

UPDATE tag t
SET t.article_count=(SELECT COUNT(*) FROM article_tag at WHERE at.tag_id=t.id);

-- 发布事件使站内搜索、文章分块和通知消费者能够从同一事实继续异步收敛。
INSERT INTO domain_event_outbox
    (event_id,aggregate_type,aggregate_id,aggregate_version,lifecycle_epoch,event_type,
     payload_version,payload_json,dedupe_key,occurred_at,state,retry_count,next_attempt_at,
     lease_owner,lease_until,last_error,created_at)
SELECT UUID_TO_BIN(UUID()),'ARTICLE',a.id,1,1,'ARTICLE_REVISION_PUBLISHED',1,
       JSON_OBJECT('articleId',a.id,'revisionId',r.id,'moderationJobId',j.id,
                   'contentHash',r.content_hash,'oldPublishedRevisionId',NULL,
                   'newPublishedRevisionId',r.id),
       CONCAT('ARTICLE:',a.id,':1:1:ARTICLE_REVISION_PUBLISHED'),
       s.published_at,'PENDING',0,CURRENT_TIMESTAMP(6),NULL,NULL,NULL,CURRENT_TIMESTAMP(6)
FROM seed_demo_article s
JOIN article a ON a.author_id=1 AND a.title=s.title
JOIN article_revision r ON r.article_id=a.id AND r.revision_no=1
JOIN article_moderation_job j ON j.article_id=a.id AND j.revision_id=r.id
WHERE NOT EXISTS (
    SELECT 1 FROM domain_event_outbox o
    WHERE o.dedupe_key=CONCAT('ARTICLE:',a.id,':1:1:ARTICLE_REVISION_PUBLISHED')
);

COMMIT;

SELECT a.id,a.title,a.status,a.visibility_state,a.published_revision_id,
       JSON_ARRAYAGG(t.name) AS tags
FROM article a
JOIN seed_demo_article s ON a.author_id=1 AND a.title=s.title
LEFT JOIN article_tag at ON at.article_id=a.id
LEFT JOIN tag t ON t.id=at.tag_id
GROUP BY a.id,a.title,a.status,a.visibility_state,a.published_revision_id
ORDER BY a.id;
