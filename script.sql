create table article
(
    id            bigint auto_increment
        primary key,
    title         varchar(100)                       not null comment '标题',
    summary       varchar(255)                       null comment '摘要',
    content       mediumtext                         null comment '内容',
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
    collect_count int      default 0                 not null,
    latest_revision_id    bigint                             null,
    pending_revision_id   bigint                             null,
    published_revision_id bigint                             null,
    visibility_state      varchar(24)                        null,
    review_state          varchar(24)                        null,
    lifecycle_epoch       bigint   default 1                 not null,
    lock_version          bigint   default 0                 not null,
    constraint uk_article_id_author
        unique (id, author_id),
    index idx_article_latest_pointer (latest_revision_id, id),
    index idx_article_pending_pointer (pending_revision_id, id),
    index idx_article_published_pointer (published_revision_id, id)
)
    comment '文章表' charset = utf8mb4 collate = utf8mb4_unicode_ci;

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
    create_time     datetime          null comment '创建时间',
    source_event_id binary(16)        null,
    constraint uk_message_source_event
        unique (source_event_id)
)
    comment '消息通知表' charset = utf8mb4 collate = utf8mb4_unicode_ci;

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
    name          varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
                               not null comment '标签名',
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
    INDEX idx_article_event_time (article_id, occurred_at DESC),
    INDEX idx_event_occurred_at (occurred_at, id),
    INDEX idx_user_article_event_at (user_id, article_id, occurred_at DESC, id DESC),
    INDEX idx_user_author_event_at (user_id, target_author_id, occurred_at DESC, id DESC)
) COMMENT='个性化推荐行为事实';

CREATE TABLE recommendation_profile_checkpoint (
    user_id BIGINT PRIMARY KEY,
    requested_event_id BIGINT NOT NULL,
    rebuilt_event_id BIGINT NOT NULL DEFAULT 0,
    needs_rebuild TINYINT NOT NULL DEFAULT 1,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(500) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_profile_checkpoint_due (needs_rebuild, next_attempt_at, user_id)
) COMMENT='推荐画像持久化重建检查点';

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
    article_author_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    tag_affinity DOUBLE NOT NULL,
    author_affinity DOUBLE NOT NULL,
    similar_score DOUBLE NOT NULL,
    heat_score DOUBLE NOT NULL,
    freshness_score DOUBLE NOT NULL,
    source_follow TINYINT NOT NULL DEFAULT 0,
    source_tag TINYINT NOT NULL DEFAULT 0,
    source_similar TINYINT NOT NULL DEFAULT 0,
    source_explore TINYINT NOT NULL DEFAULT 0,
    baseline_score DOUBLE NULL,
    exposed_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_recommendation_exposure (user_id, article_id, session_id),
    INDEX idx_exposure_user_time (user_id, exposed_at DESC),
    INDEX idx_exposure_article_time (article_id, exposed_at DESC),
    INDEX idx_exposure_user_article_at (user_id, article_id, exposed_at DESC, id DESC),
    INDEX idx_exposure_user_author_at (user_id, article_author_id, exposed_at DESC, id DESC),
    INDEX idx_exposure_training (exposed_at DESC, id DESC)
) COMMENT='推荐真实曝光和训练特征快照';

CREATE INDEX idx_article_recommendation_feed
    ON article (status, is_deleted, create_time DESC, id DESC);

CREATE TABLE article_draft (
    article_id     BIGINT PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    draft_version  BIGINT       NOT NULL,
    title          VARCHAR(100) NOT NULL,
    summary        VARCHAR(255) NULL,
    body_markdown  MEDIUMTEXT   NULL,
    body_plain     MEDIUMTEXT   NULL,
    cover          VARCHAR(255) NULL,
    tags_json      JSON         NOT NULL,
    content_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    lock_version   BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_article_draft_owner (article_id, user_id),
    CONSTRAINT fk_article_draft_owner FOREIGN KEY (article_id, user_id)
        REFERENCES article (id, author_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_revision (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id           BIGINT       NOT NULL,
    revision_no          BIGINT       NOT NULL,
    title                VARCHAR(100) NOT NULL,
    summary              VARCHAR(255) NULL,
    body_markdown        MEDIUMTEXT   NULL,
    body_plain           MEDIUMTEXT   NULL,
    cover                VARCHAR(255) NULL,
    tags_json            JSON         NOT NULL,
    content_hash         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_draft_version BIGINT       NOT NULL,
    created_by           BIGINT       NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_article_revision_no (article_id, revision_no),
    UNIQUE KEY uk_article_revision_identity (id, article_id),
    INDEX idx_article_revision_creator (article_id, created_by),
    CONSTRAINT fk_article_revision_article FOREIGN KEY (article_id)
        REFERENCES article (id) ON DELETE RESTRICT,
    CONSTRAINT fk_article_revision_creator FOREIGN KEY (article_id, created_by)
        REFERENCES article (id, author_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_moderation_job (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id       BIGINT       NOT NULL,
    revision_id      BIGINT       NOT NULL,
    content_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state            VARCHAR(24)  NOT NULL,
    model_decision   VARCHAR(16)  NULL,
    risk_score       DECIMAL(6,5) NULL,
    policy_hits_json JSON         NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(6)  NULL,
    lease_owner      VARCHAR(96)  NULL,
    lease_until      DATETIME(6)  NULL,
    last_error       VARCHAR(500) NULL,
    reviewer_id      BIGINT       NULL,
    review_reason    VARCHAR(500) NULL,
    reviewed_at      DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    lock_version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_article_moderation_revision (article_id, revision_id),
    UNIQUE KEY uk_article_moderation_identity (id, article_id),
    INDEX idx_moderation_revision_fk (revision_id, article_id),
    INDEX idx_moderation_queue (state, next_attempt_at, id),
    CONSTRAINT fk_moderation_revision FOREIGN KEY (revision_id, article_id)
        REFERENCES article_revision (id, article_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_moderation_attempt (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id                 BIGINT      NOT NULL,
    attempt_no             INT         NOT NULL,
    provider               VARCHAR(32) NULL,
    model                  VARCHAR(96) NULL,
    prompt_version         VARCHAR(32) NOT NULL,
    input_hash             CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    structured_output_json JSON        NULL,
    latency_ms             BIGINT      NOT NULL,
    token_usage_json       JSON        NULL,
    finish_reason          VARCHAR(32) NULL,
    error_code             VARCHAR(64) NULL,
    created_at             DATETIME(6) NOT NULL,
    UNIQUE KEY uk_moderation_attempt (job_id, attempt_no),
    CONSTRAINT fk_attempt_job FOREIGN KEY (job_id)
        REFERENCES article_moderation_job (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_revision_migration_issue (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id      BIGINT       NOT NULL,
    issue_code      VARCHAR(64)  NOT NULL,
    observed_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    details_json    JSON         NOT NULL,
    detected_at     DATETIME(6)  NOT NULL,
    resolved_at     DATETIME(6)  NULL,
    resolution_note VARCHAR(500) NULL,
    UNIQUE KEY uk_revision_migration_issue (article_id, issue_code),
    INDEX idx_revision_migration_unresolved (resolved_at, article_id),
    INDEX idx_revision_migration_retention (resolved_at, id),
    CONSTRAINT fk_revision_migration_article FOREIGN KEY (article_id)
        REFERENCES article (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE domain_event_outbox (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id          BINARY(16)   NOT NULL,
    aggregate_type    VARCHAR(64)  NOT NULL,
    aggregate_id      BIGINT       NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    lifecycle_epoch   BIGINT       NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    payload_version   INT          NOT NULL,
    payload_json      JSON         NOT NULL,
    dedupe_key        VARCHAR(190) NOT NULL,
    occurred_at       DATETIME(6)  NOT NULL,
    state             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME(6)  NOT NULL,
    lease_owner       VARCHAR(96)  NULL,
    lease_until       DATETIME(6)  NULL,
    last_error        VARCHAR(500) NULL,
    created_at        DATETIME(6)  NOT NULL,
    published_at      DATETIME(6)  NULL,
    failed_at         DATETIME(6)  NULL,
    dead_resolved_at  DATETIME(6)  NULL,
    dead_resolved_by  VARCHAR(96)  NULL,
    dead_resolution   VARCHAR(32)  NULL,
    UNIQUE KEY uk_domain_event_id (event_id),
    UNIQUE KEY uk_domain_event_dedupe (dedupe_key),
    INDEX idx_domain_outbox_dispatch (state, next_attempt_at, id),
    INDEX idx_domain_outbox_recovery (state, lease_until, id),
    INDEX idx_domain_outbox_published_retention (state, published_at, id),
    INDEX idx_domain_outbox_dead_retention (state, dead_resolved_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE consumer_inbox (
    consumer_name VARCHAR(96) NOT NULL,
    event_id      BINARY(16)  NOT NULL,
    processed_at  DATETIME(6) NOT NULL,
    result_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    INDEX idx_consumer_inbox_retention (processed_at, consumer_name, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_watermark (
    consumer_name        VARCHAR(96) NOT NULL,
    aggregate_type       VARCHAR(64) NOT NULL,
    aggregate_id         BIGINT      NOT NULL,
    last_applied_version BIGINT      NOT NULL DEFAULT 0,
    lifecycle_epoch      BIGINT      NOT NULL DEFAULT 0,
    tombstone            TINYINT(1)  NOT NULL DEFAULT 0,
    lease_owner          VARCHAR(96) NULL,
    lease_until          DATETIME(6) NULL,
    updated_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_name, aggregate_type, aggregate_id),
    INDEX idx_projection_lease (lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_revision_rollout_checkpoint (
    checkpoint_id             TINYINT     NOT NULL,
    mode                      VARCHAR(24) NOT NULL,
    schema_generation         BIGINT      NOT NULL,
    minimum_binary_generation BIGINT      NOT NULL,
    required_build_digest     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    backfill_started_at       DATETIME(6) NULL,
    verified_build_digest     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verified_fingerprint      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verify_report_hash        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verified_at               DATETIME(6) NULL,
    sentinel_build_digest     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sentinel_report_hash      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sentinel_verified_at      DATETIME(6) NULL,
    cutover_epoch             BIGINT      NOT NULL DEFAULT 0,
    updated_by                VARCHAR(96) NOT NULL,
    updated_at                DATETIME(6) NOT NULL,
    lock_version              BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (checkpoint_id),
    CONSTRAINT chk_article_revision_rollout_singleton CHECK (checkpoint_id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_consumer_registry (
    consumer_name          VARCHAR(96) NOT NULL,
    aggregate_type         VARCHAR(64) NOT NULL,
    state                  VARCHAR(16) NOT NULL,
    proof_mode             VARCHAR(24) NOT NULL,
    required_for_retention TINYINT(1)  NOT NULL,
    retirement_high_water_id BIGINT NULL,
    lock_version           BIGINT      NOT NULL DEFAULT 0,
    updated_by             VARCHAR(96) NOT NULL,
    updated_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_name),
    CONSTRAINT chk_projection_consumer_state
        CHECK (state IN ('ACTIVE','DRAINING','DISABLED')),
    CONSTRAINT chk_projection_consumer_proof
        CHECK (proof_mode IN ('WATERMARK','TARGET_MANIFEST')),
    CONSTRAINT chk_projection_consumer_required_state CHECK (
        (state IN ('ACTIVE','DRAINING') AND required_for_retention=1)
        OR (state='DISABLED' AND required_for_retention=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_consumer_event_type (
    consumer_name VARCHAR(96) NOT NULL,
    event_type    VARCHAR(64) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_name,event_type),
    CONSTRAINT fk_projection_consumer_event_consumer
        FOREIGN KEY (consumer_name) REFERENCES projection_consumer_registry(consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_target_registry (
    id                       BIGINT       NOT NULL,
    kind                     VARCHAR(32)  NOT NULL,
    consumer_name            VARCHAR(96)  NULL,
    physical_name            VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    read_alias               VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    schema_fingerprint       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_name               VARCHAR(64)  NULL,
    model_digest             CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dimension                INT          NULL,
    generation               BIGINT       NOT NULL,
    target_role              VARCHAR(32)  NOT NULL,
    state                    VARCHAR(16)  NOT NULL,
    required_for_retention   TINYINT(1)   NOT NULL DEFAULT 0,
    rebuild_job_id           BIGINT       NULL,
    rollback_deadline        DATETIME(6)  NULL,
    lock_version             BIGINT       NOT NULL DEFAULT 0,
    operator_identity        VARCHAR(96)  NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_projection_target_physical (kind,physical_name),
    KEY idx_projection_target_consumer_state (consumer_name,state),
    CONSTRAINT fk_projection_target_consumer
        FOREIGN KEY (consumer_name) REFERENCES projection_consumer_registry(consumer_name),
    CONSTRAINT chk_projection_target_state CHECK (
        state IN ('SCHEMA_ONLY','BUILDING','VERIFYING','ACTIVE','DRAINING','RETIRED','FAILED')),
    CONSTRAINT chk_projection_target_consumer_binding CHECK (
        (state='SCHEMA_ONLY' AND consumer_name IS NULL AND required_for_retention=0)
        OR (state<>'SCHEMA_ONLY' AND consumer_name IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_entity_manifest (
    target_id                 BIGINT      NOT NULL,
    entity_kind               VARCHAR(32) NOT NULL,
    entity_id                 BIGINT      NOT NULL,
    desired_lifecycle_epoch   BIGINT      NOT NULL,
    desired_aggregate_version BIGINT      NOT NULL,
    desired_hash              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied_lifecycle_epoch   BIGINT      NULL,
    applied_aggregate_version BIGINT      NULL,
    applied_hash              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    tombstone                 TINYINT(1)  NOT NULL DEFAULT 0,
    effect_state              VARCHAR(16) NOT NULL,
    repair_required           TINYINT(1)  NOT NULL DEFAULT 0,
    repair_next_attempt_at    DATETIME(6) NULL,
    last_error_code           VARCHAR(64) NULL,
    lock_version              BIGINT      NOT NULL DEFAULT 0,
    updated_at                DATETIME(6) NOT NULL,
    PRIMARY KEY (target_id,entity_kind,entity_id),
    KEY idx_projection_manifest_repair (repair_required,repair_next_attempt_at,target_id),
    CONSTRAINT fk_projection_manifest_target
        FOREIGN KEY (target_id) REFERENCES projection_target_registry(id),
    CONSTRAINT chk_projection_manifest_effect
        CHECK (effect_state IN ('PENDING','APPLIED','TOMBSTONE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_rebuild_job (
    id                         BIGINT       NOT NULL,
    job_kind                   VARCHAR(24)  NOT NULL,
    target_id                  BIGINT       NULL,
    target_parser_generation   BIGINT       NULL,
    status                     VARCHAR(24)  NOT NULL,
    snapshot_high_water_id     BIGINT       NOT NULL,
    last_replayed_outbox_id    BIGINT       NOT NULL DEFAULT 0,
    source_cursor              VARCHAR(256) NULL,
    lease_owner                VARCHAR(96)  NULL,
    lease_until                DATETIME(6)  NULL,
    recovery_not_before        DATETIME(6)  NULL,
    hard_deadline              DATETIME(6)  NULL,
    verification_hash          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verified_count             BIGINT       NULL,
    alias_proof_hash           CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lock_version               BIGINT       NOT NULL DEFAULT 0,
    operator_identity          VARCHAR(96)  NOT NULL,
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_projection_rebuild_status (status,lease_until,id),
    CONSTRAINT fk_projection_rebuild_target
        FOREIGN KEY (target_id) REFERENCES projection_target_registry(id),
    CONSTRAINT chk_projection_rebuild_kind
        CHECK (job_kind IN ('CHUNK_FACT','ES_TARGET','MILVUS_TARGET')),
    CONSTRAINT chk_projection_rebuild_status CHECK (
        status IN ('PENDING','RUNNING','RECOVERY_REQUIRED','VERIFYING','COMPLETE','FAILED','CANCELLED')),
    CONSTRAINT chk_projection_rebuild_binding CHECK (
        (job_kind='CHUNK_FACT' AND target_id IS NULL AND target_parser_generation IS NOT NULL)
        OR (job_kind<>'CHUNK_FACT' AND target_id IS NOT NULL AND target_parser_generation IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_rebuild_item (
    job_id                    BIGINT      NOT NULL,
    entity_kind              VARCHAR(32) NOT NULL,
    entity_id                BIGINT      NOT NULL,
    source_lifecycle_epoch   BIGINT      NOT NULL,
    source_aggregate_version BIGINT      NOT NULL,
    source_hash              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state                    VARCHAR(16) NOT NULL,
    lease_owner              VARCHAR(96) NULL,
    lease_until              DATETIME(6) NULL,
    hard_deadline            DATETIME(6) NULL,
    result_hash              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error_code          VARCHAR(64) NULL,
    lock_version             BIGINT      NOT NULL DEFAULT 0,
    updated_at               DATETIME(6) NOT NULL,
    PRIMARY KEY (job_id,entity_kind,entity_id),
    KEY idx_projection_rebuild_item_state (job_id,state,lease_until,entity_id),
    CONSTRAINT fk_projection_rebuild_item_job
        FOREIGN KEY (job_id) REFERENCES projection_rebuild_job(id),
    CONSTRAINT chk_projection_rebuild_item_state
        CHECK (state IN ('PENDING','RUNNING','APPLIED','STALE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE projection_switch_fence (
    kind                VARCHAR(32) NOT NULL,
    state               VARCHAR(16) NOT NULL,
    generation          BIGINT      NOT NULL,
    owner               VARCHAR(96) NULL,
    lease_until         DATETIME(6) NULL,
    fence_high_water_id BIGINT      NULL,
    lock_version        BIGINT      NOT NULL DEFAULT 0,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (kind),
    CONSTRAINT chk_projection_switch_fence_state
        CHECK (state IN ('OPEN','FENCING','FENCED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE article_chunk_parser_generation (
    generation                  BIGINT      NOT NULL,
    parser_version              VARCHAR(32) NOT NULL,
    token_estimator_version     VARCHAR(32) NOT NULL,
    dependency_fingerprint      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    required_build_digest       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state                       VARCHAR(16) NOT NULL,
    rollback_deadline           DATETIME(6) NULL,
    operator_identity           VARCHAR(96) NOT NULL,
    created_at                  DATETIME(6) NOT NULL,
    updated_at                  DATETIME(6) NOT NULL,
    lock_version                BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (generation),
    CONSTRAINT chk_chunk_parser_state
        CHECK (state IN ('BUILDING','ACTIVE','DRAINING','RETIRED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_chunk_parser_checkpoint (
    checkpoint_id      TINYINT     NOT NULL,
    active_generation  BIGINT      NOT NULL,
    lock_version       BIGINT      NOT NULL DEFAULT 0,
    updated_by         VARCHAR(96) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (checkpoint_id),
    CONSTRAINT chk_chunk_parser_checkpoint_singleton CHECK (checkpoint_id=1),
    CONSTRAINT fk_chunk_parser_checkpoint_generation
        FOREIGN KEY (active_generation) REFERENCES article_chunk_parser_generation(generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_chunk_set (
    article_id                BIGINT      NOT NULL,
    published_revision_id     BIGINT      NULL,
    parser_generation         BIGINT      NOT NULL,
    parser_version            VARCHAR(32) NOT NULL,
    chunk_set_version         BIGINT      NOT NULL,
    source_lifecycle_epoch    BIGINT      NOT NULL,
    source_aggregate_version  BIGINT      NOT NULL,
    chunk_set_hash            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_chunk_count        INT         NOT NULL,
    published_at              DATETIME(6) NULL,
    lock_version              BIGINT      NOT NULL DEFAULT 0,
    updated_at                DATETIME(6) NOT NULL,
    PRIMARY KEY (article_id),
    CONSTRAINT fk_chunk_set_article FOREIGN KEY (article_id) REFERENCES article(id),
    CONSTRAINT fk_chunk_set_revision FOREIGN KEY (published_revision_id,article_id)
        REFERENCES article_revision(id,article_id),
    CONSTRAINT fk_chunk_set_parser FOREIGN KEY (parser_generation)
        REFERENCES article_chunk_parser_generation(generation),
    CONSTRAINT chk_chunk_set_active_count CHECK (active_chunk_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE article_chunk (
    id                       BIGINT       NOT NULL,
    article_id               BIGINT       NOT NULL,
    revision_id              BIGINT       NOT NULL,
    chunk_no                 INT          NOT NULL,
    parser_generation        BIGINT       NOT NULL,
    parser_version           VARCHAR(32)  NOT NULL,
    title                    VARCHAR(100) NOT NULL,
    heading_path_json        VARCHAR(2000) NOT NULL,
    body_text                MEDIUMTEXT   NOT NULL,
    start_codepoint          INT          NOT NULL,
    end_codepoint            INT          NOT NULL,
    estimated_tokens         INT          NOT NULL,
    revision_content_hash    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_hash               CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_input_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    language                 VARCHAR(16)  NOT NULL,
    is_active                TINYINT(1)   NOT NULL,
    published_at             DATETIME(6)  NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_chunk_identity (revision_id,parser_generation,chunk_no),
    KEY idx_article_chunk_current (article_id,is_active,parser_generation,chunk_no),
    CONSTRAINT fk_article_chunk_article FOREIGN KEY (article_id) REFERENCES article(id),
    CONSTRAINT fk_article_chunk_revision FOREIGN KEY (revision_id,article_id)
        REFERENCES article_revision(id,article_id),
    CONSTRAINT fk_article_chunk_parser FOREIGN KEY (parser_generation)
        REFERENCES article_chunk_parser_generation(generation),
    CONSTRAINT chk_article_chunk_offsets CHECK (
        start_codepoint>=0 AND end_codepoint>=start_codepoint AND estimated_tokens>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


INSERT INTO projection_consumer_registry
    (consumer_name,aggregate_type,state,proof_mode,required_for_retention,
     retirement_high_water_id,lock_version,updated_by,updated_at)
VALUES
    ('article-search-current-pointer','ARTICLE','ACTIVE','WATERMARK',1,NULL,0,'fresh-schema',CURRENT_TIMESTAMP(6)),
    ('article-chunk-current-pointer','ARTICLE','DISABLED','WATERMARK',0,NULL,0,'fresh-schema',CURRENT_TIMESTAMP(6)),
    ('article-chunk-elasticsearch','ARTICLE_CHUNK_SET','DISABLED','TARGET_MANIFEST',0,NULL,0,'fresh-schema',CURRENT_TIMESTAMP(6)),
    ('article-chunk-milvus','ARTICLE_CHUNK_SET','DISABLED','TARGET_MANIFEST',0,NULL,0,'fresh-schema',CURRENT_TIMESTAMP(6));

INSERT INTO projection_consumer_event_type (consumer_name,event_type,created_at) VALUES
    ('article-search-current-pointer','ARTICLE_REVISION_PUBLISHED',CURRENT_TIMESTAMP(6)),
    ('article-search-current-pointer','ARTICLE_REVISION_REJECTED',CURRENT_TIMESTAMP(6)),
    ('article-search-current-pointer','ARTICLE_REVISION_SUPERSEDED',CURRENT_TIMESTAMP(6)),
    ('article-search-current-pointer','ARTICLE_UNPUBLISHED',CURRENT_TIMESTAMP(6)),
    ('article-search-current-pointer','ARTICLE_DELETED',CURRENT_TIMESTAMP(6)),
    ('article-chunk-current-pointer','ARTICLE_REVISION_PUBLISHED',CURRENT_TIMESTAMP(6)),
    ('article-chunk-current-pointer','ARTICLE_REVISION_REJECTED',CURRENT_TIMESTAMP(6)),
    ('article-chunk-current-pointer','ARTICLE_REVISION_SUPERSEDED',CURRENT_TIMESTAMP(6)),
    ('article-chunk-current-pointer','ARTICLE_UNPUBLISHED',CURRENT_TIMESTAMP(6)),
    ('article-chunk-current-pointer','ARTICLE_DELETED',CURRENT_TIMESTAMP(6)),
    ('article-chunk-elasticsearch','ARTICLE_CHUNK_REINDEX_REQUESTED',CURRENT_TIMESTAMP(6)),
    ('article-chunk-milvus','ARTICLE_CHUNK_REINDEX_REQUESTED',CURRENT_TIMESTAMP(6));

ALTER TABLE article
    ADD CONSTRAINT fk_article_latest_revision
        FOREIGN KEY (latest_revision_id, id)
            REFERENCES article_revision (id, article_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_article_pending_revision
        FOREIGN KEY (pending_revision_id, id)
            REFERENCES article_revision (id, article_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_article_published_revision
        FOREIGN KEY (published_revision_id, id)
            REFERENCES article_revision (id, article_id) ON DELETE RESTRICT;

CREATE TABLE agent_profile (
    user_id          BIGINT       NOT NULL,
    personality_text VARCHAR(2000) NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    lock_version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_agent_profile_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_run_guard (
    user_id         BIGINT      NOT NULL,
    active_run_id   BINARY(16)  NULL,
    active_run_type VARCHAR(16) NULL,
    run_fence       BIGINT      NOT NULL DEFAULT 0,
    lease_until     DATETIME(6) NULL,
    lock_version    BIGINT      NOT NULL DEFAULT 0,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_agent_run_guard_active (active_run_id),
    CONSTRAINT fk_agent_run_guard_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT chk_agent_run_guard_type CHECK (
        active_run_type IS NULL OR active_run_type IN ('PERSISTENT','TEMPORARY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_conversation (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    last_message_id BIGINT      NULL,
    memory_epoch    BIGINT      NOT NULL DEFAULT 1,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    lock_version    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_conversation_user (user_id),
    UNIQUE KEY uk_agent_conversation_id_user (id,user_id),
    CONSTRAINT fk_agent_conversation_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_episode (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    conversation_id BIGINT       NOT NULL,
    episode_no      INT          NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    active_slot     TINYINT GENERATED ALWAYS AS (CASE WHEN state='ACTIVE' THEN 1 ELSE NULL END) STORED,
    opened_at       DATETIME(6)  NOT NULL,
    sealed_at       DATETIME(6)  NULL,
    summary_text    MEDIUMTEXT   NULL,
    summary_hash    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    turn_count      INT          NOT NULL DEFAULT 0,
    token_count     BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_episode_number (conversation_id,episode_no),
    UNIQUE KEY uk_agent_episode_active (conversation_id,active_slot),
    UNIQUE KEY uk_agent_episode_id_user (id,user_id),
    CONSTRAINT fk_agent_episode_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversation(id,user_id),
    CONSTRAINT chk_agent_episode_state CHECK (
        state IN ('ACTIVE','SEALED','SUMMARIZING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_turn (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    conversation_id   BIGINT       NOT NULL,
    episode_id        BIGINT       NOT NULL,
    run_id            BINARY(16)   NOT NULL,
    client_request_id BINARY(16)   NOT NULL,
    request_hash      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_type         VARCHAR(32)  NOT NULL,
    page_context_json JSON         NOT NULL,
    grounding_mode    VARCHAR(24)  NOT NULL,
    state             VARCHAR(16)  NOT NULL,
    run_fence         BIGINT       NOT NULL,
    lease_until       DATETIME(6)  NULL,
    error_code        VARCHAR(64)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    started_at        DATETIME(6)  NULL,
    completed_at      DATETIME(6)  NULL,
    expires_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_turn_run (run_id),
    UNIQUE KEY uk_agent_turn_request (conversation_id,client_request_id),
    UNIQUE KEY uk_agent_turn_id_user (id,user_id),
    KEY idx_agent_turn_recovery (state,lease_until,id),
    CONSTRAINT fk_agent_turn_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversation(id,user_id),
    CONSTRAINT fk_agent_turn_episode FOREIGN KEY (episode_id,user_id)
        REFERENCES agent_episode(id,user_id),
    CONSTRAINT chk_agent_turn_state CHECK (
        state IN ('RECEIVED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    turn_id         BIGINT       NOT NULL,
    conversation_id BIGINT       NOT NULL,
    episode_id      BIGINT       NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    content         MEDIUMTEXT   NOT NULL,
    content_hash    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    completed_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_message_turn_role (turn_id,role),
    UNIQUE KEY uk_agent_message_id_user (id,user_id),
    KEY idx_agent_message_timeline (conversation_id,id DESC),
    CONSTRAINT fk_agent_message_turn FOREIGN KEY (turn_id,user_id)
        REFERENCES agent_turn(id,user_id),
    CONSTRAINT fk_agent_message_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversation(id,user_id),
    CONSTRAINT fk_agent_message_episode FOREIGN KEY (episode_id,user_id)
        REFERENCES agent_episode(id,user_id),
    CONSTRAINT chk_agent_message_role CHECK (role IN ('USER','ASSISTANT')),
    CONSTRAINT chk_agent_message_state CHECK (state IN ('PARTIAL','FINAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_tool_call (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    turn_id         BIGINT       NOT NULL,
    ordinal         INT          NOT NULL,
    tool_name       VARCHAR(64)  NOT NULL,
    arguments_json  JSON         NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    result_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    duration_ms     BIGINT       NULL,
    error_code      VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    completed_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool_call_ordinal (turn_id,ordinal),
    CONSTRAINT fk_agent_tool_call_turn FOREIGN KEY (turn_id,user_id)
        REFERENCES agent_turn(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_retrieval_hit (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    turn_id          BIGINT       NOT NULL,
    source_type      VARCHAR(24)  NOT NULL,
    source_key       VARCHAR(160) NOT NULL,
    article_id       BIGINT       NULL,
    revision_id      BIGINT       NULL,
    chunk_id         BIGINT       NULL,
    memory_id        BIGINT       NULL,
    bm25_score       DOUBLE       NULL,
    dense_score      DOUBLE       NULL,
    rrf_score        DOUBLE       NOT NULL,
    rank_no          INT          NOT NULL,
    excerpt_snapshot VARCHAR(1000) NULL,
    metadata_json    JSON         NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_retrieval_source (turn_id,source_type,source_key),
    KEY idx_agent_retrieval_expiry (expires_at,id),
    CONSTRAINT fk_agent_retrieval_turn FOREIGN KEY (turn_id,user_id)
        REFERENCES agent_turn(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_answer_citation (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    assistant_message_id BIGINT       NOT NULL,
    ordinal              INT          NOT NULL,
    article_id           BIGINT       NOT NULL,
    revision_id          BIGINT       NOT NULL,
    chunk_id             BIGINT       NOT NULL,
    title_snapshot       VARCHAR(100) NOT NULL,
    quote_snapshot       VARCHAR(1000) NOT NULL,
    quote_hash           CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state                VARCHAR(16)  NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    redacted_at          DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_citation_ordinal (assistant_message_id,ordinal),
    CONSTRAINT fk_agent_citation_message FOREIGN KEY (assistant_message_id,user_id)
        REFERENCES agent_message(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE agent_conversation
    ADD CONSTRAINT fk_agent_conversation_last_message
        FOREIGN KEY (last_message_id,user_id) REFERENCES agent_message(id,user_id);

CREATE TABLE agent_memory_setting (
    user_id BIGINT NOT NULL, enabled TINYINT(1) NOT NULL DEFAULT 1,
    sensitive_projection_enabled TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (user_id),
    CONSTRAINT fk_agent_memory_setting_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_memory_item (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    current_version_id BIGINT NULL, category VARCHAR(24) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (id),
    UNIQUE KEY uk_agent_memory_item_id_user (id,user_id),
    KEY idx_agent_memory_owner_state (user_id,state,updated_at,id),
    CONSTRAINT fk_agent_memory_item_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT chk_agent_memory_category CHECK (category IN ('PREFERENCE','GOAL','PROFILE')),
    CONSTRAINT chk_agent_memory_sensitivity CHECK (sensitivity IN ('LOW','SENSITIVE')),
    CONSTRAINT chk_agent_memory_state CHECK (state IN ('ACTIVE','PAUSED','DELETED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_memory_version (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    memory_id BIGINT NOT NULL, version_no BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL, normalized_content VARCHAR(1000) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL, created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_agent_memory_version_no (memory_id,version_no),
    UNIQUE KEY uk_agent_memory_version_id_user (id,user_id),
    UNIQUE KEY uk_agent_memory_version_owner (id,memory_id,user_id),
    UNIQUE KEY uk_agent_memory_owner_hash (user_id,content_hash),
    CONSTRAINT fk_agent_memory_version_item FOREIGN KEY (memory_id,user_id)
        REFERENCES agent_memory_item(id,user_id),
    CONSTRAINT chk_agent_memory_version_state CHECK (state IN ('ACTIVE','SUPERSEDED','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_memory_source (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    memory_id BIGINT NOT NULL, memory_version_id BIGINT NOT NULL,
    source_turn_id BIGINT NOT NULL, source_message_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL, PRIMARY KEY (id),
    UNIQUE KEY uk_agent_memory_source_message (memory_id,source_message_id),
    CONSTRAINT fk_agent_memory_source_item FOREIGN KEY (memory_id,user_id)
        REFERENCES agent_memory_item(id,user_id),
    CONSTRAINT fk_agent_memory_source_version FOREIGN KEY (memory_version_id,memory_id,user_id)
        REFERENCES agent_memory_version(id,memory_id,user_id),
    CONSTRAINT fk_agent_memory_source_turn FOREIGN KEY (source_turn_id,user_id)
        REFERENCES agent_turn(id,user_id),
    CONSTRAINT fk_agent_memory_source_message FOREIGN KEY (source_message_id,user_id)
        REFERENCES agent_message(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_memory_projection (
    memory_version_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL, embedding_model VARCHAR(64) NULL,
    projected_at DATETIME(6) NULL, last_error_code VARCHAR(64) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (memory_version_id),
    KEY idx_agent_memory_projection_recovery (state,memory_version_id),
    CONSTRAINT fk_agent_memory_projection_version FOREIGN KEY (memory_version_id,user_id)
        REFERENCES agent_memory_version(id,user_id),
    CONSTRAINT chk_agent_memory_projection_state CHECK (
        state IN ('PENDING','PROJECTED','DELETING','DELETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE agent_memory_item
    ADD CONSTRAINT fk_agent_memory_current_version
        FOREIGN KEY (current_version_id,id,user_id)
        REFERENCES agent_memory_version(id,memory_id,user_id);

-- 用户自带模型配置只保存 AES-GCM 密文。浏览器只会拿到 key_hint，永不取回密文或明文。
CREATE TABLE user_ai_provider_setting (
    user_id            BIGINT       NOT NULL,
    provider           VARCHAR(24)  NOT NULL,
    base_url           VARCHAR(512) NOT NULL,
    model              VARCHAR(128) NOT NULL,
    encrypted_api_key  TEXT         NOT NULL,
    key_hint           VARCHAR(16)  NOT NULL,
    enabled            TINYINT(1)   NOT NULL DEFAULT 1,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    lock_version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_ai_provider_owner FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT chk_user_ai_provider_type CHECK (
        provider IN ('OPENAI','DEEPSEEK','QWEN','CUSTOM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
