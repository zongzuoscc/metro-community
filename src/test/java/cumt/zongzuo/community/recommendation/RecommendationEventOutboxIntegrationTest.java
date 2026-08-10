package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.dto.CommentDTO;
import cumt.zongzuo.community.dto.LikeTaskDTO;
import cumt.zongzuo.community.dto.NotificationMsgDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mq.LikeConsumer;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventOutbox;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.mapper.RecommendationEventOutboxMapper;
import cumt.zongzuo.community.recommendation.service.RecommendationEventOutboxService;
import cumt.zongzuo.community.recommendation.task.RecommendationOutboxDispatcher;
import cumt.zongzuo.community.service.CommentService;
import cumt.zongzuo.community.service.FavoriteService;
import cumt.zongzuo.community.service.FollowService;
import cumt.zongzuo.community.service.LikeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

class RecommendationEventOutboxIntegrationTest extends IntegrationTestSupport {

    private static final String EVENT_QUEUE = "recommendation.event.queue";

    @Autowired
    private FavoriteService favoriteService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private FollowService followService;
    @Autowired
    private LikeService likeService;
    @Autowired
    private LikeConsumer likeConsumer;
    @Autowired
    private RecommendationEventOutboxService outboxService;
    @Autowired
    private RecommendationEventOutboxMapper outboxMapper;
    @Autowired
    private RecommendationOutboxDispatcher dispatcher;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RecommendationProperties recommendationProperties;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM recommendation_event_outbox");
        jdbcTemplate.update("DELETE FROM like_record");
        jdbcTemplate.update("DELETE FROM favorite");
        jdbcTemplate.update("DELETE FROM favorite_folder WHERE id = 3");
        jdbcTemplate.update("DELETE FROM follow");
        jdbcTemplate.update("DELETE FROM comment");
        jdbcTemplate.update("DELETE FROM article");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        purge(EVENT_QUEUE);
        purge("like.task.queue");
        purge("comment.task.queue");
        purge("message.notify.queue");
    }

    @AfterEach
    void restoreFollowNotificationTemplate() {
        ReflectionTestUtils.setField(followService, "rabbitTemplate", rabbitTemplate);
    }

    @Test
    void committedFavoritePersistsOutboxWithoutPublishingRecommendationInRequest() {
        assertThat(recommendationProperties.isEnabled()).isFalse();
        jdbcTemplate.update("""
                INSERT INTO favorite_folder (id,user_id,name,description,is_public,create_time)
                VALUES (3,7,'outbox test folder','owned fixture',0,NOW(6))
                """);

        favoriteService.toggleFavorite(7L, 21L, 3L);

        List<RecommendationEventOutbox> rows = outboxRows();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("COLLECT");
            assertThat(row.getDedupeKey()).isEqualTo("collect:" + favoriteId());
            assertThat(row.getStatus()).isEqualTo("PENDING");
        });
        assertThat(rabbitTemplate.receive(EVENT_QUEUE, 50)).isNull();

        favoriteService.toggleFavorite(7L, 21L, 3L);
        assertThat(outboxRows()).hasSize(1);
    }

    @Test
    void committedCommentPersistsOutboxButOuterRollbackDoesNot() {
        CommentDTO committed = comment(21L, "committed");
        commentService.publishComment(committed, 7L);

        assertThat(outboxRows()).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("COMMENT");
            assertThat(row.getDedupeKey()).startsWith("comment:");
            assertThat(row.getArticleId()).isEqualTo(21L);
        });

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            commentService.publishComment(comment(22L, "rolled back"), 8L);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outboxRows()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE article_id = 22", Long.class)).isZero();
    }

    @Test
    void firstFollowPersistsOutboxAndUnfollowDoesNot() {
        followService.follow(7L, 9L);

        assertThat(outboxRows()).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("FOLLOW_AUTHOR");
            assertThat(row.getArticleId()).isNull();
            assertThat(row.getTargetAuthorId()).isEqualTo(9L);
            assertThat(row.getDedupeKey()).startsWith("follow:");
        });

        followService.follow(7L, 9L);
        assertThat(outboxRows()).hasSize(1);
    }

    @Test
    void notificationPublishFailureDoesNotRollbackFollowOrRecommendationOutbox() {
        RabbitTemplate failingNotificationTemplate = Mockito.mock(RabbitTemplate.class);
        doThrow(new AmqpException("notification broker unavailable"))
                .when(failingNotificationTemplate)
                .convertAndSend(eq("message.notify.queue"), any(NotificationMsgDTO.class));
        ReflectionTestUtils.setField(followService, "rabbitTemplate", failingNotificationTemplate);

        assertThatCode(() -> followService.follow(7L, 9L)).doesNotThrowAnyException();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE follower_id = 7 AND followed_id = 9", Long.class)).isEqualTo(1L);
        assertThat(outboxRows()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo("PENDING");
            assertThat(row.getEventType()).isEqualTo("FOLLOW_AUTHOR");
        });
        assertThat(redisTemplate.opsForSet().isMember("user:following:7", "9")).isTrue();
    }

    @Test
    void outboxFailureRollsBackFollowButDuplicateFollowRemainsIdempotent() {
        Long nextFollowId = jdbcTemplate.queryForObject("""
                SELECT AUTO_INCREMENT FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'follow'
                """, Long.class);
        outboxService.enqueue(command("follow:" + nextFollowId));

        assertThatThrownBy(() -> followService.follow(7L, 9L))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE follower_id = 7 AND followed_id = 9", Long.class)).isZero();

        jdbcTemplate.update("DELETE FROM recommendation_event_outbox");
        jdbcTemplate.update("INSERT INTO follow (follower_id, followed_id, create_time) VALUES (7, 9, NOW())");
        redisTemplate.delete("user:following:7");

        followService.follow(7L, 9L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE follower_id = 7 AND followed_id = 9", Long.class)).isEqualTo(1L);
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    void likeEventExistsOnlyAfterArticleLikeRecordWasInserted() {
        insertArticle(21L, 9L);

        likeService.like(7L, 21L, 1);

        assertThat(outboxRows()).isEmpty();
        LikeTaskDTO task = (LikeTaskDTO) rabbitTemplate.receiveAndConvert("like.task.queue", 2_000);
        assertThat(task).isNotNull();

        likeConsumer.handle(task);

        Long likeRecordId = jdbcTemplate.queryForObject(
                "SELECT id FROM like_record WHERE user_id = 7 AND target_id = 21 AND target_type = 1", Long.class);
        assertThat(outboxRows()).singleElement().satisfies(row -> {
            assertThat(row.getEventType()).isEqualTo("LIKE");
            assertThat(row.getDedupeKey()).isEqualTo("like:" + likeRecordId);
        });

        likeConsumer.handle(task);
        assertThat(outboxRows()).hasSize(1);
    }

    @Test
    void unlikeAndCommentLikeDoNotPersistRecommendationEvents() {
        LikeTaskDTO commentLike = likeTask(7L, 31L, 2, true);
        likeConsumer.handle(commentLike);
        likeConsumer.handle(likeTask(7L, 31L, 2, false));
        likeConsumer.handle(likeTask(7L, 21L, 1, false));

        assertThat(outboxRows()).isEmpty();
    }

    @Test
    void confirmedDispatchMarksOutboxSentAndDeliversCommand() {
        RecommendationEventCommand command = command("dispatch:confirmed");
        outboxService.enqueue(command);

        dispatcher.dispatchPending();

        RecommendationEventOutbox row = outboxByDedupe(command.dedupeKey());
        assertThat(row.getStatus()).isEqualTo("SENT");
        assertThat(row.getSentTime()).isNotNull();
        assertThat(rabbitTemplate.receiveAndConvert(EVENT_QUEUE, 2_000)).isEqualTo(command);
    }

    @Test
    void conditionalClaimAllowsOnlyOneDispatcherToOwnARow() throws Exception {
        RecommendationEventCommand command = command("dispatch:claim");
        outboxService.enqueue(command);
        RecommendationEventOutbox row = outboxByDedupe(command.dedupeKey());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Integer> firstClaim = executor.submit(() -> claimAfterBarrier(barrier, row.getId()));
            Future<Integer> secondClaim = executor.submit(() -> claimAfterBarrier(barrier, row.getId()));

            assertThat(firstClaim.get() + secondClaim.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(outboxMapper.selectById(row.getId()).getStatus()).isEqualTo("SENDING");
    }

    @Test
    void staleSendingRowIsRecoveredAndDispatched() {
        RecommendationEventOutbox stale = RecommendationEventOutbox.pending(command("dispatch:stale"));
        stale.setStatus("SENDING");
        stale.setUpdateTime(LocalDateTime.now().minusMinutes(6));
        outboxMapper.insert(stale);

        dispatcher.dispatchPending();

        assertThat(outboxMapper.selectById(stale.getId()).getStatus()).isEqualTo("SENT");
        assertThat(rabbitTemplate.receiveAndConvert(EVENT_QUEUE, 2_000)).isEqualTo(command("dispatch:stale"));
    }

    @Test
    void failedDispatchKeepsBusinessRowAndSchedulesExponentialRetry() {
        RecommendationEventCommand command = command("dispatch:failure");
        outboxService.enqueue(command);
        RecommendationEventOutbox row = outboxByDedupe(command.dedupeKey());
        LocalDateTime attemptedAt = LocalDateTime.now();
        RecommendationOutboxDispatcher failingDispatcher = new RecommendationOutboxDispatcher(
                outboxMapper, ignored -> { throw new IllegalStateException("broker unavailable"); });

        failingDispatcher.dispatchPending();

        RecommendationEventOutbox retried = outboxMapper.selectById(row.getId());
        assertThat(retried.getStatus()).isEqualTo("PENDING");
        assertThat(retried.getRetryCount()).isEqualTo(1);
        assertThat(retried.getNextAttemptAt()).isAfter(attemptedAt.plusSeconds(1));
        assertThat(retried.getNextAttemptAt()).isAfter(retried.getUpdateTime());
        assertThat(retried.getLastError()).contains("broker unavailable");
    }

    private List<RecommendationEventOutbox> outboxRows() {
        return outboxMapper.selectList(null);
    }

    private RecommendationEventOutbox outboxByDedupe(String dedupeKey) {
        return outboxMapper.selectList(null).stream()
                .filter(row -> dedupeKey.equals(row.getDedupeKey()))
                .findFirst()
                .orElseThrow();
    }

    private Long favoriteId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM favorite WHERE user_id = 7 AND article_id = 21", Long.class);
    }

    private CommentDTO comment(long articleId, String content) {
        CommentDTO dto = new CommentDTO();
        dto.setArticleId(articleId);
        dto.setContent(content);
        return dto;
    }

    private LikeTaskDTO likeTask(long userId, long targetId, int targetType, boolean like) {
        LikeTaskDTO task = new LikeTaskDTO();
        task.setUserId(userId);
        task.setTargetId(targetId);
        task.setTargetType(targetType);
        task.setLike(like);
        return task;
    }

    private RecommendationEventCommand command(String dedupeKey) {
        return new RecommendationEventCommand(7L, 21L, null, RecommendationEventType.LIKE,
                LocalDateTime.of(2026, 8, 9, 12, 0), dedupeKey, "test");
    }

    private void insertArticle(long articleId, long authorId) {
        Article article = new Article();
        article.setId(articleId);
        article.setTitle("Outbox integration article");
        article.setAuthorId(authorId);
        article.setLikeCount(0);
        jdbcTemplate.update("INSERT INTO article (id, title, author_id, like_count) VALUES (?, ?, ?, ?)",
                article.getId(), article.getTitle(), article.getAuthorId(), article.getLikeCount());
    }

    private void purge(String queue) {
        if (amqpAdmin.getQueueProperties(queue) != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }

    private int claimAfterBarrier(CyclicBarrier barrier, Long rowId) throws Exception {
        barrier.await();
        return outboxMapper.claim(rowId, LocalDateTime.now().withNano(0));
    }
}
