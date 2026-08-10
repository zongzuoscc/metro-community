package cumt.zongzuo.community.article;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.FavoriteService;
import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.repository.ArticleRepository;
import cumt.zongzuo.community.recommendation.service.RecommendationFeedService;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidate;
import cumt.zongzuo.community.recommendation.service.RecommendationCandidateService;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeedResponse;
import cumt.zongzuo.community.recommendation.dto.RecommendationMode;
import cumt.zongzuo.community.recommendation.dto.RecommendationSession;
import cumt.zongzuo.community.recommendation.dto.RecommendationSessionItem;
import cumt.zongzuo.community.recommendation.service.RecommendationSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.util.List;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = {
        "metro.article.revision-mode=CUTOVER",
        "recommendation.enabled=true",
        "recommendation.feed-request-limit=1000"
})
class ArticlePublishedPointerIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 95_001L;
    private static final long VIEWER_ID = 95_002L;
    private static final long ARTICLE_ID = 95_101L;
    private static final long FOLDER_ID = 95_201L;
    private static final String PUBLISHED_HASH = "a".repeat(64);

    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private ArticleService articleService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ElasticsearchOperations elasticsearchOperations;
    @Autowired
    private RecommendationFeedService recommendationFeedService;
    @Autowired
    private RecommendationCandidateService recommendationCandidateService;
    @Autowired
    private RecommendationSessionStore recommendationSessionStore;
    @Autowired
    private FavoriteService favoriteService;

    @BeforeEach
    void cleanAndSeedSentinels() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        try {
            articleRepository.deleteAll();
        } catch (RuntimeException indexNotCreatedYet) {
            // save() below creates the index on the first ES-backed test.
        }
        jdbcTemplate.update("DELETE FROM follow WHERE follower_id=? OR followed_id=?", VIEWER_ID, AUTHOR_ID);
        jdbcTemplate.update("DELETE FROM recommendation_exposure WHERE user_id=?", VIEWER_ID);
        jdbcTemplate.update("DELETE FROM favorite WHERE folder_id=?", FOLDER_ID);
        jdbcTemplate.update("DELETE FROM favorite_folder WHERE id=?", FOLDER_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id BETWEEN 95101 AND 95109");
        jdbcTemplate.update("""
                DELETE FROM article_moderation_attempt
                WHERE job_id IN (
                    SELECT id FROM article_moderation_job
                    WHERE article_id BETWEEN 95101 AND 95109
                )
                """);
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id BETWEEN 95101 AND 95109");
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id BETWEEN 95101 AND 95109");
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id BETWEEN 95101 AND 95109");
        jdbcTemplate.update("DELETE FROM article_tag WHERE article_id BETWEEN 95101 AND 95109");
        jdbcTemplate.update("DELETE FROM article_revision_migration_issue WHERE article_id BETWEEN 95101 AND 95109");
        jdbcTemplate.update("DELETE FROM article WHERE id BETWEEN 95101 AND 95109");
        jdbcTemplate.update("""
                INSERT INTO sys_user (id,username,password,email,role,status)
                VALUES (?, 'pointer-author', 'unused', 'pointer-author@example.com', 0, 0),
                       (?, 'pointer-viewer', 'unused', 'pointer-viewer@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE status=0
                """, AUTHOR_ID, VIEWER_ID);
        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,view_count,like_count,comment_count,collect_count,
                     create_time,update_time,status,cover,is_deleted,visibility_state,review_state,
                     lifecycle_epoch,lock_version)
                VALUES (?, 'LEGACY_TITLE', 'LEGACY_SUMMARY', 'LEGACY_BODY', ?,0,0,0,0,
                        NOW(6),NOW(6),1,'LEGACY_COVER',0,'PUBLIC','HUMAN_PENDING',1,0)
                """, ARTICLE_ID, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                VALUES (?,1,'PUBLISHED_TITLE','PUBLISHED_SUMMARY','PUBLISHED_BODY','PUBLISHED_BODY',
                        'PUBLISHED_COVER',JSON_ARRAY('published'),?,1,?,NOW(6))
                """, ARTICLE_ID, PUBLISHED_HASH, AUTHOR_ID);
        long publishedRevisionId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=? AND revision_no=1", Long.class, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                VALUES (?,2,'PENDING_TITLE','PENDING_SUMMARY','PENDING_BODY','PENDING_BODY',
                        'PENDING_COVER',JSON_ARRAY('pending'),?,2,?,NOW(6))
                """, ARTICLE_ID, "b".repeat(64), AUTHOR_ID);
        long pendingRevisionId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=? AND revision_no=2", Long.class, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_draft
                    (article_id,user_id,draft_version,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,created_at,updated_at,lock_version)
                VALUES (?, ?, 3, 'DRAFT_TITLE','DRAFT_SUMMARY','DRAFT_BODY','DRAFT_BODY','DRAFT_COVER',
                        JSON_ARRAY('draft'),?,NOW(6),NOW(6),0)
                """, ARTICLE_ID, AUTHOR_ID, "c".repeat(64));
        jdbcTemplate.update("""
                UPDATE article
                SET latest_revision_id=?,pending_revision_id=?,published_revision_id=?
                WHERE id=?
                """, pendingRevisionId, pendingRevisionId, publishedRevisionId, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO follow (follower_id,followed_id,create_time)
                VALUES (?,?,NOW(6))
                """, VIEWER_ID, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO favorite_folder (id,user_id,name,description,is_public,create_time)
                VALUES (?,?,'private pointer folder','sentinel folder',0,NOW(6))
                """, FOLDER_ID, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO favorite (user_id,article_id,folder_id,create_time)
                VALUES (?,?,?,NOW(6))
                """, AUTHOR_ID, ARTICLE_ID, FOLDER_ID);
    }

    @Test
    void publicMapperReadsOnlyCurrentPublishedRevision() {
        Article article = articleMapper.selectPublicById(ARTICLE_ID);

        assertThat(article).isNotNull();
        assertThat(article.getTitle()).isEqualTo("PUBLISHED_TITLE");
        assertThat(article.getSummary()).isEqualTo("PUBLISHED_SUMMARY");
        assertThat(article.getContent()).isEqualTo("PUBLISHED_BODY");
        assertThat(article.getCover()).isEqualTo("PUBLISHED_COVER");
        assertThat(article.getPublishedRevisionId()).isNotNull();
        assertThat(article.getContentHash()).isEqualTo(PUBLISHED_HASH);
        assertThat(article.getRevisionTagsJson()).contains("published");
    }

    @Test
    void everyPublicMapperPathRehydratesThePublishedSnapshot() {
        List<List<Article>> paths = List.of(
                articleMapper.selectPublishedByIds(List.of(ARTICLE_ID)),
                articleMapper.selectPublishedHotFresh(VIEWER_ID, List.of(), 10),
                articleMapper.selectPublishedChronological(null, null, 10),
                articleMapper.selectPublishedByFollowedAuthors(VIEWER_ID, VIEWER_ID, List.of(), 10));

        for (List<Article> articles : paths) {
            assertThat(articles).singleElement().satisfies(article -> {
                assertThat(article.getId()).isEqualTo(ARTICLE_ID);
                assertThat(article.getTitle()).isEqualTo("PUBLISHED_TITLE");
                assertThat(article.getContent()).isEqualTo("PUBLISHED_BODY");
                assertThat(article.getContentHash()).isEqualTo(PUBLISHED_HASH);
            });
        }
        assertThat(articleMapper.selectPublishedChronologicalIds(10)).containsExactly(ARTICLE_ID);
    }

    @Test
    void pointerPresenceAloneDoesNotOverrideVisibilityOrDeletion() {
        jdbcTemplate.update("UPDATE article SET visibility_state='PRIVATE' WHERE id=?", ARTICLE_ID);
        assertThat(articleMapper.selectPublicById(ARTICLE_ID)).isNull();
        assertThat(articleMapper.selectPublishedByIds(List.of(ARTICLE_ID))).isEmpty();

        jdbcTemplate.update("UPDATE article SET visibility_state='PUBLIC',is_deleted=1 WHERE id=?", ARTICLE_ID);
        assertThat(articleMapper.selectPublicById(ARTICLE_ID)).isNull();
        assertThat(articleMapper.selectPublishedChronological(null, null, 10)).isEmpty();
    }

    @Test
    void publicDetailIsStrictlyPublishedAndCacheIdentityChangesWithThePointer() {
        Article first = articleService.getDetail(ARTICLE_ID);
        assertThat(first.getContent()).isEqualTo("PUBLISHED_BODY");
        assertThat(first.getTagList()).containsExactly("published");

        Long replacementId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=? AND revision_no=2",
                Long.class, ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET published_revision_id=? WHERE id=?", replacementId, ARTICLE_ID);

        Article replacement = articleService.getDetail(ARTICLE_ID);
        assertThat(replacement.getContent()).isEqualTo("PENDING_BODY");
        assertThat(replacement.getContentHash()).isEqualTo("b".repeat(64));

        jdbcTemplate.update("UPDATE article SET visibility_state='PRIVATE' WHERE id=?", ARTICLE_ID);
        assertThatThrownBy(() -> articleService.getDetail(ARTICLE_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    void ownerEditingReadsOnlyTheMutableDraft() {
        Article draft = articleService.getArticleForEdit(ARTICLE_ID, AUTHOR_ID);

        assertThat(draft.getTitle()).isEqualTo("DRAFT_TITLE");
        assertThat(draft.getContent()).isEqualTo("DRAFT_BODY");
        assertThat(draft.getDraftVersion()).isEqualTo(3L);
        assertThat(draft.getTagList()).containsExactly("draft");
        assertThatThrownBy(() -> articleService.getArticleForEdit(ARTICLE_ID, VIEWER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    void publicArticleServiceListsNeverReadTheLegacyMirror() {
        List<Article> records = List.of(
                articleService.getHotArticles().getFirst(),
                articleService.getFeedArticles(null).getFirst(),
                articleService.getHotRank().getFirst(),
                articleService.getUserArticles(AUTHOR_ID, 1, 10).getRecords().getFirst(),
                articleService.getFollowArticles(VIEWER_ID, 1, 10).getRecords().getFirst(),
                articleService.searchArticles("", 1, 10).getRecords().getFirst(),
                articleService.getHotArticles7Days().getFirst());

        assertThat(records).allSatisfy(article -> {
            assertThat(article.getId()).isEqualTo(ARTICLE_ID);
            assertThat(article.getTitle()).isEqualTo("PUBLISHED_TITLE");
            if (article.getContent() != null) {
                assertThat(article.getContent()).isEqualTo("PUBLISHED_BODY");
            }
        });
    }

    @Test
    void cutoverAuthorListsProjectTheOwnersDraftInsteadOfAnyPublicOrLegacyBody() {
        assertThat(articleService.getMyDrafts(AUTHOR_ID)).singleElement().satisfies(article -> {
            assertThat(article.getContent()).isEqualTo("DRAFT_BODY");
            assertThat(article.getDraftVersion()).isEqualTo(3L);
        });
        assertThat(articleService.getDraftCount(AUTHOR_ID)).isEqualTo(1L);
        assertThat(articleService.getMyAllArticles(AUTHOR_ID, 1, 10).getRecords())
                .singleElement()
                .satisfies(article -> assertThat(article.getContent()).isEqualTo("DRAFT_BODY"));
        assertThat(articleService.getMyDrafts(VIEWER_ID)).isEmpty();
    }

    @Test
    void cutoverDraftBoxContainsOnlyChangesAfterTheLatestSubmission() {
        jdbcTemplate.update("""
                UPDATE article_draft
                SET title='PENDING_TITLE',summary='PENDING_SUMMARY',body_markdown='PENDING_BODY',
                    body_plain='PENDING_BODY',cover='PENDING_COVER',tags_json=JSON_ARRAY('pending'),
                    content_hash=?,updated_at=NOW(6)
                WHERE article_id=? AND user_id=?
                """, "b".repeat(64), ARTICLE_ID, AUTHOR_ID);

        assertThat(articleService.getMyDrafts(AUTHOR_ID)).isEmpty();
        assertThat(articleService.getDraftCount(AUTHOR_ID)).isZero();
        assertThat(articleService.getMyAllArticles(AUTHOR_ID, 1, 10).getRecords())
                .singleElement()
                .satisfies(article -> assertThat(article.getContent()).isEqualTo("PENDING_BODY"));

        jdbcTemplate.update("""
                UPDATE article_draft
                SET title='UNSAVED_TITLE',body_markdown='UNSAVED_BODY',body_plain='UNSAVED_BODY',
                    content_hash=?,updated_at=NOW(6)
                WHERE article_id=? AND user_id=?
                """, "f".repeat(64), ARTICLE_ID, AUTHOR_ID);

        assertThat(articleService.getMyDrafts(AUTHOR_ID))
                .singleElement()
                .satisfies(article -> assertThat(article.getContent()).isEqualTo("UNSAVED_BODY"));
        assertThat(articleService.getDraftCount(AUTHOR_ID)).isEqualTo(1L);
        assertThat(articleService.getMyDrafts(VIEWER_ID)).isEmpty();
    }

    @Test
    void elasticsearchIsOnlyCandidateRecallAndMysqlRehydratesAuthorizedContent() {
        ArticleDoc staleProjection = new ArticleDoc();
        staleProjection.setId(ARTICLE_ID);
        staleProjection.setTitle("pointer-search-needle");
        staleProjection.setSummary("ES_LEGACY_SUMMARY");
        staleProjection.setContent("ES_LEGACY_BODY");
        staleProjection.setCover("ES_LEGACY_COVER");
        staleProjection.setAuthorId(AUTHOR_ID);
        staleProjection.setCreateTime(java.time.LocalDateTime.now().withNano(0));
        articleRepository.save(staleProjection);
        elasticsearchOperations.indexOps(ArticleDoc.class).refresh();

        assertThat(articleService.searchArticles("pointer-search-needle", 1, 10).getRecords())
                .singleElement()
                .satisfies(article -> {
                    assertThat(article.getTitle()).isEqualTo("PUBLISHED_TITLE");
                    assertThat(article.getContent()).isEqualTo("PUBLISHED_BODY");
                    assertThat(article.getSummary()).isEqualTo("PUBLISHED_SUMMARY");
                });

        jdbcTemplate.update("UPDATE article SET visibility_state='PRIVATE' WHERE id=?", ARTICLE_ID);
        assertThat(articleService.searchArticles("pointer-search-needle", 1, 10).getRecords()).isEmpty();
    }

    @Test
    void searchSkipsHigherRankedStaleCandidatesBeforePaginatingAuthorizedResults() {
        String keyword = "overfetchsentinel";
        long firstLiveId = 95_102L;
        long secondLiveId = 95_103L;
        insertPublicCandidate(firstLiveId);
        insertPublicCandidate(secondLiveId);
        List<ArticleDoc> staleDocuments = new java.util.ArrayList<>();
        for (long staleId = 95_900L; staleId < 96_401L; staleId++) {
            staleDocuments.add(similarityDocument(staleId, (keyword + " ").repeat(20)));
        }
        articleRepository.saveAll(staleDocuments);
        ArticleDoc firstLive = similarityDocument(firstLiveId, "unrelated body one");
        firstLive.setTitle(keyword);
        ArticleDoc secondLive = similarityDocument(secondLiveId, "unrelated body two");
        secondLive.setTitle(keyword);
        articleRepository.saveAll(List.of(firstLive, secondLive));
        elasticsearchOperations.indexOps(ArticleDoc.class).refresh();

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Article> firstPage =
                articleService.searchArticles(keyword, 1, 1);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Article> secondPage =
                articleService.searchArticles(keyword, 2, 1);

        assertThat(firstPage.getRecords()).hasSize(1);
        assertThat(secondPage.getRecords()).hasSize(1);
        assertThat(java.util.stream.Stream.concat(firstPage.getRecords().stream(), secondPage.getRecords().stream())
                .map(Article::getId)).containsExactlyInAnyOrder(firstLiveId, secondLiveId);
        assertThat(firstPage.getTotal()).isEqualTo(2L);
        assertThat(secondPage.getTotal()).isEqualTo(2L);
    }

    @Test
    void searchFailsClosedInsteadOfPublishingPartialResultsBeyondItsCandidateLimit() {
        String keyword = "searchcapsentinel";
        List<ArticleDoc> candidatesBeyondTheBound = new java.util.ArrayList<>();
        for (long staleId = 120_000L; staleId < 121_001L; staleId++) {
            candidatesBeyondTheBound.add(similarityDocument(staleId, keyword));
        }
        articleRepository.saveAll(candidatesBeyondTheBound);
        elasticsearchOperations.indexOps(ArticleDoc.class).refresh();

        assertThatThrownBy(() -> articleService.searchArticles(keyword, 1, 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SEARCH_CANDIDATE_WINDOW_INCOMPLETE");
    }

    @Test
    void similarArticlesUseEsOnlyForIdsAndDropStaleCandidates() {
        long candidateId = 95_102L;
        insertPublicCandidate(candidateId);
        articleRepository.save(similarityDocument(ARTICLE_ID, "ES_TARGET_BODY semanticbridge semanticbridge"));
        articleRepository.save(similarityDocument(candidateId, "ES_STALE_BODY semanticbridge semanticbridge"));
        for (long staleId = 95_900L; staleId < 95_905L; staleId++) {
            articleRepository.save(similarityDocument(staleId,
                    "ES_ONLY_BODY semanticbridge semanticbridge"));
        }
        elasticsearchOperations.indexOps(ArticleDoc.class).refresh();

        assertThat(articleService.getSimilarArticles(ARTICLE_ID, 5))
                .singleElement()
                .satisfies(article -> {
                    assertThat(article.getId()).isEqualTo(candidateId);
                    assertThat(article.getTitle()).isEqualTo("CANDIDATE_PUBLISHED_TITLE");
                    assertThat(article.getContent()).isEqualTo("CANDIDATE_PUBLISHED_BODY");
                });

        jdbcTemplate.update("UPDATE article SET visibility_state='PRIVATE' WHERE id=?", candidateId);
        assertThat(articleService.getSimilarArticles(ARTICLE_ID, 5)).isEmpty();
    }

    @Test
    void recommendationSessionDropsARevisionThatChangedAfterRankingWithoutExposure() {
        long candidateId = 95_102L;
        insertPublicCandidate(candidateId);

        RecommendationFeedResponse firstPage = recommendationFeedService.feed(VIEWER_ID, null, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotBlank();
        long firstId = firstPage.items().getFirst().article().getId();
        long secondId = firstId == ARTICLE_ID ? candidateId : ARTICLE_ID;

        replacePublishedPointer(secondId);
        RecommendationFeedResponse secondPage = recommendationFeedService.feed(
                VIEWER_ID, firstPage.nextCursor(), 1);

        assertThat(secondPage.items()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_exposure WHERE user_id=? AND article_id=?",
                Integer.class, VIEWER_ID, secondId)).isZero();
    }

    @Test
    void cutoverRejectsLegacySessionItemsWithoutPublishedIdentityAndRecordsNoExposure() {
        String sessionId = UUID.randomUUID().toString();
        recommendationSessionStore.save(sessionId, new RecommendationSession(
                VIEWER_ID,
                List.of(new RecommendationSessionItem(ARTICLE_ID, null, "CHRONOLOGICAL")),
                RecommendationMode.COLD_START));
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((sessionId + ":0").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        RecommendationFeedResponse response = recommendationFeedService.feed(VIEWER_ID, cursor, 1);

        assertThat(response.items()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_exposure WHERE user_id=?",
                Integer.class, VIEWER_ID)).isZero();
    }

    @Test
    void cutoverTagRecallUsesPublishedRevisionTagsInsteadOfLegacyArticleTags() {
        redisTemplate.opsForZSet().add("recommendation:tag:" + VIEWER_ID, "published", 10D);

        List<RecommendationCandidate> candidates = recommendationCandidateService.recallAndAssemble(
                VIEWER_ID, java.util.Set.of());

        assertThat(candidates).filteredOn(candidate -> candidate.articleId().equals(ARTICLE_ID))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.sources()).contains(RecommendationCandidate.Source.TAG);
                    assertThat(candidate.tags()).containsExactly("published");
                    assertThat(candidate.article().getContent()).isEqualTo("PUBLISHED_BODY");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE article_id=?", Integer.class, ARTICLE_ID)).isZero();
    }

    @Test
    void favoriteFolderRequiresOwnerOrPublicAccessAndRehydratesPublishedContent() {
        assertThatThrownBy(() -> favoriteService.getFolderDetail(FOLDER_ID, VIEWER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));

        assertThat(favoriteService.getFolderDetail(FOLDER_ID, AUTHOR_ID).get("articles"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Article.class))
                .singleElement()
                .satisfies(article -> assertThat(article.getContent()).isEqualTo("PUBLISHED_BODY"));

        jdbcTemplate.update("UPDATE favorite_folder SET is_public=1 WHERE id=?", FOLDER_ID);
        assertThat(favoriteService.getFolderDetail(FOLDER_ID, VIEWER_ID).get("articles"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Article.class))
                .singleElement()
                .satisfies(article -> assertThat(article.getContent()).isEqualTo("PUBLISHED_BODY"));
    }

    @Test
    void favoriteWritesCannotTargetAnotherUsersFolder() {
        assertThatThrownBy(() -> favoriteService.toggleFavorite(VIEWER_ID, ARTICLE_ID, FOLDER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE folder_id=? AND user_id=?",
                Integer.class, FOLDER_ID, VIEWER_ID)).isZero();
    }

    @Test
    void hotFeedCacheStoresOnlyIdsAndAlwaysRehydratesCurrentPublicPointers() {
        redisTemplate.opsForValue().set("hot:article:rank:7days", """
                [{"id":95101,"title":"HOT_CACHE_LEAK","content":"HOT_CACHE_LEAK"}]
                """);

        assertThat(articleService.getHotArticles7Days())
                .singleElement()
                .satisfies(article -> assertThat(article.getContent()).isEqualTo("PUBLISHED_BODY"));

        articleService.updateHotRankCache();
        assertThat(redisTemplate.opsForValue().get("hot:article:rank:7days"))
                .isEqualTo("[\"95101\"]");

        jdbcTemplate.update("UPDATE article SET visibility_state='PRIVATE' WHERE id=?", ARTICLE_ID);
        assertThat(articleService.getHotArticles7Days()).isEmpty();
    }

    @Test
    void cutoverAdminPendingListReadsThePendingRevisionWithoutReplacingPublicContent() {
        assertThat(articleService.getPendingArticles(1, 10).getRecords())
                .singleElement()
                .satisfies(article -> {
                    assertThat(article.getTitle()).isEqualTo("PENDING_TITLE");
                    assertThat(article.getContent()).isEqualTo("PENDING_BODY");
                    assertThat(article.getTagList()).containsExactly("pending");
                });
        assertThat(articleService.getDetail(ARTICLE_ID).getContent()).isEqualTo("PUBLISHED_BODY");
    }

    @Test
    void cutoverRecycleBinReadsOnlyTheOwnersDraftRevision() {
        jdbcTemplate.update("UPDATE article SET is_deleted=1,delete_time=NOW(6) WHERE id=?", ARTICLE_ID);

        assertThat(articleService.getRecycleBin(AUTHOR_ID))
                .singleElement()
                .satisfies(article -> {
                    assertThat(article.getId()).isEqualTo(ARTICLE_ID);
                    assertThat(article.getContent()).isEqualTo("DRAFT_BODY");
                    assertThat(article.getDraftVersion()).isEqualTo(3L);
                    assertThat(article.getTagList()).containsExactly("draft");
                });
        assertThat(articleService.getRecycleBin(VIEWER_ID)).isEmpty();
    }

    private void insertPublicCandidate(long articleId) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,view_count,like_count,comment_count,collect_count,
                     create_time,update_time,status,cover,is_deleted,visibility_state,review_state,
                     lifecycle_epoch,lock_version)
                VALUES (?, 'CANDIDATE_LEGACY_TITLE','CANDIDATE_LEGACY_SUMMARY','CANDIDATE_LEGACY_BODY',
                        ?,0,0,0,0,NOW(6),NOW(6),1,'',0,'PUBLIC','APPROVED',1,0)
                """, articleId, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                VALUES (?,1,'CANDIDATE_PUBLISHED_TITLE','CANDIDATE_PUBLISHED_SUMMARY',
                        'CANDIDATE_PUBLISHED_BODY','CANDIDATE_PUBLISHED_BODY','',JSON_ARRAY('candidate'),
                        ?,1,?,NOW(6))
                """, articleId, "d".repeat(64), AUTHOR_ID);
        Long revisionId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=?", Long.class, articleId);
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?
                """, revisionId, revisionId, articleId);
    }

    private ArticleDoc similarityDocument(long id, String body) {
        ArticleDoc document = new ArticleDoc();
        document.setId(id);
        document.setTitle(body);
        document.setSummary(body);
        document.setContent(body);
        document.setAuthorId(AUTHOR_ID);
        document.setCreateTime(java.time.LocalDateTime.now().withNano(0));
        return document;
    }

    private void replacePublishedPointer(long articleId) {
        Long existingSecond = jdbcTemplate.queryForObject(
                "SELECT MAX(CASE WHEN revision_no=2 THEN id END) FROM article_revision WHERE article_id=?",
                Long.class, articleId);
        Long replacementId = existingSecond;
        if (replacementId == null) {
            jdbcTemplate.update("""
                    INSERT INTO article_revision
                        (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                         content_hash,source_draft_version,created_by,created_at)
                    VALUES (?,2,'REPLACEMENT_TITLE','REPLACEMENT_SUMMARY','REPLACEMENT_BODY',
                            'REPLACEMENT_BODY','',JSON_ARRAY('replacement'),?,2,?,NOW(6))
                    """, articleId, "e".repeat(64), AUTHOR_ID);
            replacementId = jdbcTemplate.queryForObject(
                    "SELECT id FROM article_revision WHERE article_id=? AND revision_no=2",
                    Long.class, articleId);
        }
        jdbcTemplate.update("UPDATE article SET published_revision_id=? WHERE id=?", replacementId, articleId);
    }
}
