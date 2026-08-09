create table article
(
    id            bigint auto_increment
        primary key,
    title         varchar(100)                       not null comment '标题',
    summary       varchar(255)                       null comment '摘要',
    content       text                               null comment '内容',
    author_id     bigint                             not null comment '作者ID',
    view_count    int      default 0                 null comment '阅读数',
    like_count    int      default 0                 null comment '点赞数',
    create_time   datetime default CURRENT_TIMESTAMP null,
    update_time   datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    status        tinyint  default 1                 null comment '0-草稿, 1-已发布',
    cover         varchar(255)                       null comment '文章封面图',
    is_deleted    tinyint  default 0                 null comment '0-正常, 1-回收站',
    delete_time   datetime                           null comment '删除时间',
    comment_count int      default 0                 null comment '评论数',
    collect_count int      default 0                 not null
)
    comment '文章表' charset = utf8mb4;

create table article_tag
(
    id         bigint auto_increment
        primary key,
    article_id bigint not null comment '文章ID',
    tag_id     bigint not null comment '标签ID',
    constraint uk_article_tag
        unique (article_id, tag_id)
)
    comment '文章标签关联表' charset = utf8mb4;

create table chat_msg
(
    id          bigint auto_increment
        primary key,
    from_id     bigint            not null comment '发送者ID',
    to_id       bigint            not null comment '接收者ID',
    content     varchar(1000)     null comment '内容',
    create_time datetime          null comment '发送时间',
    status      tinyint default 0 null comment '0-未读, 1-已读'
)
    comment '私信聊天记录表' charset = utf8mb4;

create index idx_from_to
    on chat_msg (from_id, to_id);

create index idx_to_from
    on chat_msg (to_id, from_id);

create table comment
(
    id             bigint auto_increment
        primary key,
    article_id     bigint           not null comment '文章ID',
    user_id        bigint           not null comment '发送者ID',
    content        varchar(1024)    not null comment '评论内容',
    parent_id      bigint default 0 null comment '父评论ID (0表示根评论)',
    target_user_id bigint           null comment '被回复的人的ID (仅子评论有效)',
    like_count     int    default 0 null comment '点赞数',
    create_time    datetime         null,
    is_deleted     int    default 0 null comment '0-正常, 1-已删除'
)
    comment '评论表' charset = utf8mb4;

create index idx_article_id
    on comment (article_id);

create table favorite
(
    id          bigint auto_increment
        primary key,
    user_id     bigint   not null,
    article_id  bigint   not null,
    folder_id   bigint   not null comment '归属的收藏夹ID',
    create_time datetime null,
    constraint uk_user_article
        unique (user_id, article_id) comment '同一篇文章只能收藏一次'
)
    comment '收藏记录表' charset = utf8mb4;

create table favorite_folder
(
    id          bigint auto_increment
        primary key,
    user_id     bigint               not null comment '所属用户',
    name        varchar(50)          not null comment '收藏夹名称 (默认：默认收藏夹)',
    description varchar(255)         null comment '描述',
    is_public   tinyint(1) default 1 null comment '是否公开',
    create_time datetime             null
)
    comment '收藏夹' charset = utf8mb4;

create table follow
(
    id          bigint auto_increment
        primary key,
    follower_id bigint       not null comment '粉丝ID (谁关注)',
    followed_id bigint       not null comment '博主ID (关注了谁)',
    create_time datetime     null,
    remark      varchar(50)  null comment '备注名',
    description varchar(200) null comment '描述',
    constraint uk_relation
        unique (follower_id, followed_id)
)
    comment '用户关注表' charset = utf8mb4;

create table like_record
(
    id          bigint auto_increment
        primary key,
    user_id     bigint   not null comment '点赞人',
    target_id   bigint   not null comment '点赞目标ID (文章ID或评论ID)',
    target_type tinyint  not null comment '类型: 1-文章, 2-评论',
    create_time datetime null,
    constraint uk_user_target
        unique (user_id, target_id, target_type) comment '防止重复点赞'
)
    comment '点赞记录表' charset = utf8mb4;

create table message
(
    id          bigint auto_increment comment '主键ID'
        primary key,
    from_id     bigint            not null comment '发送者ID',
    to_id       bigint            not null comment '接收者ID',
    type        tinyint           not null comment '类型: 1-点赞, 2-评论, 3-关注, 4-系统通知',
    target_id   bigint            null comment '关联的目标ID(文章ID等)',
    content     varchar(500)      null comment '消息内容(评论摘要等)',
    status      tinyint default 0 null comment '状态: 0-未读, 1-已读',
    create_time datetime          null comment '创建时间'
)
    comment '消息通知表' charset = utf8mb4;

create index idx_to_id_status
    on message (to_id, status)
    comment '用于快速查询未读数';

create table report
(
    id          bigint auto_increment
        primary key,
    reporter_id bigint        not null comment '举报人ID',
    target_id   bigint        not null comment '目标ID',
    target_type int           not null comment '类型: 1-文章, 2-评论, 3-用户',
    reason      varchar(255)  null comment '举报理由',
    status      int default 0 null comment '0-待处理, 1-已处理, 2-已驳回',
    create_time datetime      null,
    handle_time datetime      null,
    handler_id  bigint        null comment '处理人(管理员)ID',
    result      varchar(255)  null comment '处理结果备注'
);

create table sys_user
(
    id          bigint auto_increment comment '主键ID'
        primary key,
    username    varchar(50)                          not null comment '用户名',
    password    varchar(100)                         not null comment '加密后的密码',
    email       varchar(100)                         null comment '邮箱',
    avatar      varchar(255)                         null comment '头像URL',
    intro       varchar(200)                         null comment '个人简介',
    create_time datetime   default CURRENT_TIMESTAMP null comment '创建时间',
    update_time datetime   default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    deleted     tinyint(1) default 0                 null comment '逻辑删除 0未删 1已删',
    role        int        default 0                 null comment '角色: 0-普通用户, 1-管理员',
    status      int        default 0                 not null comment '账号状态',
    ban_time    datetime                             null comment '封禁截止时间',
    constraint uk_email
        unique (email),
    constraint uk_username
        unique (username)
)
    comment '用户表' charset = utf8mb4;

create table tag
(
    id            bigint auto_increment comment '主键'
        primary key,
    name          varchar(50)   not null comment '标签名',
    article_count int default 0 null comment '该标签下的文章数(冗余字段用于排序)',
    create_time   datetime      null,
    constraint uk_name
        unique (name)
)
    comment '标签表' charset = utf8mb4;

CREATE TABLE user_article_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    article_id BIGINT NULL,
    target_author_id BIGINT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    source VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_article_event_dedupe UNIQUE (dedupe_key),
    INDEX idx_user_event_time (user_id, occurred_at DESC),
    INDEX idx_article_event_time (article_id, occurred_at DESC)
) COMMENT='个性化推荐行为事实';

CREATE TABLE recommendation_event_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    article_id BIGINT NULL,
    target_author_id BIGINT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    source VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(500) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_time DATETIME NULL,
    UNIQUE KEY uk_recommendation_outbox_dedupe (dedupe_key),
    INDEX idx_recommendation_outbox_dispatch (status, next_attempt_at, id)
) COMMENT='推荐事件事务 Outbox';

CREATE TABLE recommendation_exposure (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    tag_affinity DOUBLE NOT NULL,
    author_affinity DOUBLE NOT NULL,
    similar_score DOUBLE NOT NULL,
    heat_score DOUBLE NOT NULL,
    freshness_score DOUBLE NOT NULL,
    exposed_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_recommendation_exposure (user_id, article_id, session_id),
    INDEX idx_exposure_user_time (user_id, exposed_at DESC),
    INDEX idx_exposure_article_time (article_id, exposed_at DESC)
) COMMENT='推荐真实曝光和训练特征快照';
