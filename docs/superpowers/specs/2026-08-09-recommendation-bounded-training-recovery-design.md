# 推荐训练与画像补偿有界化设计

## 目标

消除离线训练和画像补偿中会随历史数据无界增长的数据库访问，同时保留“数据不完整时不发布新模型”的安全边界。

## 方案选择

曝光 cohort 有三种可选实现：继续在 MySQL 上对 90 天全量数据做窗口排序，用定时分区表预聚合，或先按 `(exposed_at, id)` 索引读取最新的有界集合再在 Java 内做用户与全局配额。本次选择第三种：它不引入新中间件或预计算表，且能将单次读取上限明确配置为 `training-exposure-scan-limit=200000`。查询读取上限加一条 sentinel；出现 sentinel 时返回独立的 `EXPOSURE_SCAN_LIMIT_EXCEEDED` 状态，训练服务不发布、不替换 active model。

## 数据模型

`recommendation_exposure` 新增 `article_author_id` 快照。新曝光在 feed 已批量加载文章的边界直接写入作者 ID，不为每条曝光追加查询。`FOLLOW_AUTHOR` 归因只访问曝光快照，通过 `(user_id, article_author_id, exposed_at DESC, id DESC)` 定位最后触点，不再 join `article`。前向迁移先以可空列接纳旧数据，再用仍存在的 article 行合理回填；无法回填的孤儿旧曝光不伪造作者。新安装列为非空。

`recommendation_profile_checkpoint` 新增 `needs_rebuild TINYINT`。新请求和失败重试均保持为 `1`，只有 `rebuilt_event_id` 真正追平当前 `requested_event_id` 时才改为 `0`。补偿查询以 `(needs_rebuild, next_attempt_at, user_id)` 索引读取 `needs_rebuild=1` 的行，完成历史不再参与每 30 秒的扫描。请求、完成和失败更新继续使用单调与条件 SQL，旧完成者不能覆盖新请求。

## 验证与迁移

每个边界都使用 Java 21 和真实 MySQL Testcontainers 做 RED→GREEN：曝光扫描恰好上限可用、上限加一拒绝发布；作者快照能在原 article 作者变更后继续正确归因；完成 checkpoint 不占用 pending 批次。迁移脚本在 MySQL 8 上对旧表执行两次，验证列、索引、回填和状态均幂等。
