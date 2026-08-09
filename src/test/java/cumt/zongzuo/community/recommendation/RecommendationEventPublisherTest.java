package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.dto.CommentDTO;
import cumt.zongzuo.community.entity.Comment;
import cumt.zongzuo.community.entity.Favorite;
import cumt.zongzuo.community.entity.Follow;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.FavoriteMapper;
import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import cumt.zongzuo.community.recommendation.service.RecommendationEventPublisher;
import cumt.zongzuo.community.service.impl.CommentServiceImpl;
import cumt.zongzuo.community.service.impl.FavoriteServiceImpl;
import cumt.zongzuo.community.service.impl.FollowServiceImpl;
import cumt.zongzuo.community.service.impl.LikeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private FavoriteMapper favoriteMapper;

    @Mock
    private ArticleMapper articleMapper;

    private RecommendationEventPublisher publisher;
    private LikeServiceImpl likeService;
    private FavoriteServiceImpl favoriteService;
    private CommentServiceImpl commentService;
    private FollowServiceImpl followService;

    @BeforeEach
    void setUp() {
        publisher = new RecommendationEventPublisher(rabbitTemplate);
        likeService = new LikeServiceImpl();
        ReflectionTestUtils.setField(likeService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(likeService, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(likeService, "recommendationEventPublisher", publisher);

        favoriteService = new FavoriteServiceImpl();
        ReflectionTestUtils.setField(favoriteService, "favoriteMapper", favoriteMapper);
        ReflectionTestUtils.setField(favoriteService, "recommendationEventPublisher", publisher);

        commentService = org.mockito.Mockito.spy(new CommentServiceImpl());
        ReflectionTestUtils.setField(commentService, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(commentService, "articleMapper", articleMapper);
        ReflectionTestUtils.setField(commentService, "recommendationEventPublisher", publisher);

        followService = org.mockito.Mockito.spy(new FollowServiceImpl());
        ReflectionTestUtils.setField(followService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(followService, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(followService, "recommendationEventPublisher", publisher);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publisherDefersRabbitSendUntilTransactionCommit() {
        RecommendationEventCommand command = command(RecommendationEventType.LIKE, 21L, null, "like:7:article:21:20260809120000000");
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(command);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), org.mockito.ArgumentMatchers.<Object>any());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(rabbitTemplate).convertAndSend(RecommendationEventPublisher.QUEUE, command);
    }

    @Test
    void publisherSendsImmediatelyWhenNoTransactionIsActive() {
        RecommendationEventCommand command = command(RecommendationEventType.LIKE, 21L, null, "like:7:article:21:20260809120000000");

        publisher.publishAfterCommit(command);

        verify(rabbitTemplate).convertAndSend(RecommendationEventPublisher.QUEUE, command);
    }

    @Test
    void publisherDoesNotSendWhenTransactionRollsBack() {
        RecommendationEventCommand command = command(RecommendationEventType.LIKE, 21L, null, "like:7:article:21:20260809120000000");
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(command);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(rabbitTemplate, never()).convertAndSend(anyString(), org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void articleLikeCreatesLikeEventButCommentLikeDoesNot() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("like:article:21", "7")).thenReturn(false, true);
        when(setOperations.isMember("like:comment:22", "7")).thenReturn(false);

        likeService.like(7L, 21L, 1);
        likeService.like(7L, 21L, 1);
        likeService.like(7L, 22L, 2);

        ArgumentCaptor<RecommendationEventCommand> events = ArgumentCaptor.forClass(RecommendationEventCommand.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(RecommendationEventPublisher.QUEUE), events.capture());
        RecommendationEventCommand event = events.getValue();
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.articleId()).isEqualTo(21L);
        assertThat(event.eventType()).isEqualTo(RecommendationEventType.LIKE);
        assertThat(event.source()).isEqualTo("article_detail");
        assertThat(event.dedupeKey()).matches("like:7:article:21:[0-9]{17}");
    }

    @Test
    void onlyFavoriteInsertionCreatesCollectEvent() {
        when(favoriteMapper.selectOne(any())).thenReturn(null, favorite(88L));
        doAnswer(invocation -> {
            Favorite favorite = invocation.getArgument(0);
            favorite.setId(501L);
            return 1;
        }).when(favoriteMapper).insert(any(Favorite.class));

        favoriteService.toggleFavorite(7L, 21L, 3L);
        favoriteService.toggleFavorite(7L, 21L, 3L);

        ArgumentCaptor<RecommendationEventCommand> events = ArgumentCaptor.forClass(RecommendationEventCommand.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(RecommendationEventPublisher.QUEUE), events.capture());
        RecommendationEventCommand event = events.getValue();
        assertThat(event.eventType()).isEqualTo(RecommendationEventType.COLLECT);
        assertThat(event.articleId()).isEqualTo(21L);
        assertThat(event.dedupeKey()).isEqualTo("collect:501");
        assertThat(event.source()).isEqualTo("favorite");
    }

    @Test
    void savedCommentCreatesCommentEvent() {
        doAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(601L);
            return true;
        }).when(commentService).save(any(Comment.class));
        CommentDTO dto = new CommentDTO();
        dto.setArticleId(21L);
        dto.setContent("useful article");
        dto.setTargetUserId(9L);

        commentService.publishComment(dto, 7L);

        ArgumentCaptor<RecommendationEventCommand> events = ArgumentCaptor.forClass(RecommendationEventCommand.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(RecommendationEventPublisher.QUEUE), events.capture());
        RecommendationEventCommand event = events.getValue();
        assertThat(event.eventType()).isEqualTo(RecommendationEventType.COMMENT);
        assertThat(event.articleId()).isEqualTo(21L);
        assertThat(event.dedupeKey()).isEqualTo("comment:601");
        assertThat(event.source()).isEqualTo("comment");
    }

    @Test
    void onlyNewFollowCreatesFollowAuthorEvent() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("user:following:7", "9")).thenReturn(false, true);
        doAnswer(invocation -> {
            Follow follow = invocation.getArgument(0);
            follow.setId(701L);
            return true;
        }).when(followService).save(any(Follow.class));
        org.mockito.Mockito.doReturn(true).when(followService).remove(any());

        followService.follow(7L, 9L);
        followService.follow(7L, 9L);

        ArgumentCaptor<RecommendationEventCommand> events = ArgumentCaptor.forClass(RecommendationEventCommand.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(RecommendationEventPublisher.QUEUE), events.capture());
        RecommendationEventCommand event = events.getValue();
        assertThat(event.eventType()).isEqualTo(RecommendationEventType.FOLLOW_AUTHOR);
        assertThat(event.articleId()).isNull();
        assertThat(event.targetAuthorId()).isEqualTo(9L);
        assertThat(event.dedupeKey()).isEqualTo("follow:701");
        assertThat(event.source()).isEqualTo("follow");
    }

    private RecommendationEventCommand command(RecommendationEventType eventType, Long articleId, Long targetAuthorId,
                                                String dedupeKey) {
        return new RecommendationEventCommand(7L, articleId, targetAuthorId, eventType,
                LocalDateTime.of(2026, 8, 9, 12, 0), dedupeKey, "test");
    }

    private Favorite favorite(Long id) {
        Favorite favorite = new Favorite();
        favorite.setId(id);
        return favorite;
    }
}
