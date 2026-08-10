package cumt.zongzuo.community.article.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.persistence.ArticleMigrationMapper;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.apache.http.util.EntityUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DefaultStageBArticleMigrationVerifier implements StageBArticleMigrationVerifier {

    private static final DateTimeFormatter ES_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LEGACY_BACKFILL_MANUAL = "LEGACY_BACKFILL_MANUAL";
    private static final String LEGACY_SHADOW_MANUAL = "LEGACY_SHADOW_MANUAL";

    private final JdbcTemplate jdbc;
    private final ElasticsearchClient elasticsearch;
    private final RestClient elasticsearchRestClient;
    private final ArticleContentCanonicalizer canonicalizer;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ArticleRevisionModeResolver modeResolver;
    private final StageBMigrationProperties properties;
    private final StageBArticleFingerprintService fingerprintService;
    private final StageBRevisionAwareStateValidator revisionAwareValidator;

    public DefaultStageBArticleMigrationVerifier(JdbcTemplate jdbc,
                                                 ElasticsearchClient elasticsearch,
                                                 RestClient elasticsearchRestClient,
                                                 ArticleContentCanonicalizer canonicalizer,
                                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                                 ArticleRevisionModeResolver modeResolver,
                                                 StageBMigrationProperties properties,
                                                 StageBArticleFingerprintService fingerprintService) {
        this.jdbc = jdbc;
        this.elasticsearch = elasticsearch;
        this.elasticsearchRestClient = elasticsearchRestClient;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
        this.modeResolver = modeResolver;
        this.properties = properties;
        this.fingerprintService = fingerprintService;
        this.revisionAwareValidator = new StageBRevisionAwareStateValidator(
                canonicalizer, objectMapper);
    }

    @Override
    public StageBMigrationReport verifyAll() {
        if (modeResolver.current() != ArticleRevisionMode.VERIFY_FENCE) {
            throw new IllegalStateException("promotion verification requires VERIFY_FENCE");
        }
        int pageSize = verifiedPageSize();
        MismatchCollector mismatches = new MismatchCollector(properties.getMaximumReportedMismatches());
        LocalDateTime startedAt = databaseNow();
        String startFingerprint = fingerprintService.fingerprint();

        long articleCount = count("article");
        long draftCount = count("article_draft");
        long revisionOneCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE revision_no=1", Long.class);
        long revisionCount = count("article_revision");
        long moderationJobCount = count("article_moderation_job");
        long expectedPublicDocumentCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM article WHERE status=1 AND is_deleted=0
                """, Long.class);
        long unresolvedArticleCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT article_id) FROM article_revision_migration_issue
                WHERE resolved_at IS NULL
                """, Long.class);
        if (unresolvedArticleCount > 0) {
            mismatches.add("UNRESOLVED_MIGRATION_ISSUE", null,
                    "unresolvedArticleCount=" + unresolvedArticleCount);
        }
        if (articleCount != draftCount + unresolvedArticleCount) {
            mismatches.add("ARTICLE_DRAFT_COUNT_MISMATCH", null,
                    "articles=" + articleCount + ",drafts=" + draftCount
                            + ",unresolvedArticles=" + unresolvedArticleCount);
        }
        if (articleCount != revisionOneCount + unresolvedArticleCount) {
            mismatches.add("ARTICLE_BASELINE_COUNT_MISMATCH", null,
                    "articles=" + articleCount + ",revisionOne=" + revisionOneCount
                            + ",unresolvedArticles=" + unresolvedArticleCount);
        }

        VerificationScan mysql = verifyMysql(pageSize, mismatches);
        verifyEveryRevisionHash(pageSize, mismatches);
        ElasticsearchScan es = verifyElasticsearch(expectedPublicDocumentCount, pageSize, mismatches);

        String endFingerprint = fingerprintService.fingerprint();
        LocalDateTime finishedAt = databaseNow();
        if (!startFingerprint.equals(endFingerprint)) {
            mismatches.add("VERIFY_FENCE_FINGERPRINT_CHANGED", null,
                    "database state changed during verification");
        }
        long windowWrites = countWindowWrites(startedAt, finishedAt);
        if (windowWrites != 0) {
            mismatches.add("VERIFY_FENCE_WRITE_DETECTED", null,
                    "rowsWithWriteTimestampInsideWindow=" + windowWrites);
        }

        return new StageBMigrationReport(mismatches.count() == 0, startedAt, finishedAt,
                startFingerprint, endFingerprint, articleCount, draftCount, revisionOneCount,
                revisionCount, moderationJobCount, unresolvedArticleCount,
                expectedPublicDocumentCount, es.documentCount(), mysql.pages(), es.pages(),
                es.maximumLookupBatchSize(), mismatches.count(), mismatches.items());
    }

    private VerificationScan verifyMysql(int pageSize, MismatchCollector mismatches) {
        long cursor = 0;
        int pages = 0;
        while (true) {
            List<VerifierArticle> articles = jdbc.query("""
                    SELECT id,title,summary,content,cover,author_id,view_count,like_count,
                           comment_count,collect_count,create_time,update_time,status,is_deleted,
                           latest_revision_id,pending_revision_id,published_revision_id,
                           visibility_state,review_state,lock_version
                    FROM article WHERE id>? ORDER BY id LIMIT ?
                    """, (rs, rowNum) -> article(rs), cursor, pageSize);
            if (articles.isEmpty()) {
                break;
            }
            pages++;
            for (VerifierArticle article : articles) {
                cursor = article.id();
                verifyArticle(article, mismatches);
            }
            if (articles.size() < pageSize) {
                break;
            }
        }
        return new VerificationScan(pages);
    }

    private void verifyArticle(VerifierArticle article, MismatchCollector mismatches) {
        if (article.status() == null || article.status() < 0 || article.status() > 3
                || article.isDeleted() == null
                || (article.isDeleted() != 0 && article.isDeleted() != 1)) {
            mismatches.add("INVALID_LEGACY_STATE", article.id(), "status/delete flag is invalid");
            return;
        }
        List<String> legacyTags = ArticleMigrationMapper.selectLegacyTags(jdbc, article.id());
        ArticleContentSnapshot legacy = canonicalizer.canonicalize(article.title(), article.summary(),
                article.content(), article.cover(), legacyTags);
        StoredDraft draft = loadDraft(article.id());
        StageBRevisionAwareStateValidator.Validation revisionAware =
                validateRevisionAware(article, draft, legacy);
        if (revisionAware.classification()
                != StageBRevisionAwareStateValidator.Classification.LEGACY) {
            if (revisionAware.classification()
                    == StageBRevisionAwareStateValidator.Classification.NEEDS_BASELINE) {
                mismatches.add("BASELINE_REVISION_MISSING", article.id(),
                        "final backfill did not freeze the native draft baseline");
            }
            revisionAware.violations().forEach(violation ->
                    mismatches.add(violation.code(), article.id(), violation.detail()));
            return;
        }
        if (draft == null) {
            mismatches.add("DRAFT_MISSING", article.id(), "article has no current draft");
        } else {
            ArticleContentSnapshot draftSnapshot = canonicalizeStored(draft.title(), draft.summary(),
                    draft.bodyMarkdown(), draft.cover(), draft.tagsJson());
            if (draft.userId() != article.authorId() || draftSnapshot == null
                    || !legacy.contentHash().equals(draftSnapshot.contentHash())
                    || !draft.contentHash().equals(draftSnapshot.contentHash())
                    || !Objects.equals(draft.bodyPlain(), draftSnapshot.bodyPlain())) {
                mismatches.add("DRAFT_HASH_MISMATCH", article.id(),
                        "draft does not match the current canonical legacy snapshot");
            }
        }

        StoredRevision baseline = loadRevisionOne(article.id());
        if (baseline == null) {
            mismatches.add("BASELINE_REVISION_MISSING", article.id(), "revision 1 is missing");
        } else if (baseline.articleId() != article.id() || baseline.createdBy() != article.authorId()) {
            mismatches.add("BASELINE_REVISION_OWNER_MISMATCH", article.id(),
                    "revision 1 belongs to a different owner/article");
        }

        String expectedVisibility = article.isDeleted() == 1 ? "RECYCLED"
                : article.status() == 1 ? "PUBLIC" : "PRIVATE";
        String expectedReview = switch (article.status()) {
            case 0 -> "NOT_SUBMITTED";
            case 1 -> "APPROVED";
            case 2 -> "AUTO_PENDING";
            case 3 -> "REJECTED";
            default -> throw new IllegalStateException("validated status changed");
        };
        if (!expectedVisibility.equals(article.visibilityState())) {
            mismatches.add("ARTICLE_VISIBILITY_MISMATCH", article.id(),
                    "visibility does not match legacy state");
        }
        if (!expectedReview.equals(article.reviewState())) {
            mismatches.add("ARTICLE_REVIEW_STATE_MISMATCH", article.id(),
                    "review state does not match legacy state");
        }

        switch (article.status()) {
            case 0 -> verifyStatusZero(article, mismatches);
            case 1 -> verifyPublished(article, legacy, mismatches);
            case 2 -> verifyPending(article, legacy, mismatches);
            case 3 -> verifyRejected(article, legacy, mismatches);
            default -> throw new IllegalStateException("validated status changed");
        }
    }

    private StageBRevisionAwareStateValidator.Validation validateRevisionAware(
            VerifierArticle article, StoredDraft draft, ArticleContentSnapshot legacy) {
        StageBRevisionAwareStateValidator.DraftState draftState = draft == null ? null
                : new StageBRevisionAwareStateValidator.DraftState(
                draft.articleId(), draft.userId(), draft.title(), draft.summary(),
                draft.bodyMarkdown(), draft.bodyPlain(), draft.cover(), draft.tagsJson(),
                draft.contentHash());
        List<StageBRevisionAwareStateValidator.RevisionState> revisions =
                loadRevisions(article.id()).stream()
                        .map(revision -> new StageBRevisionAwareStateValidator.RevisionState(
                                revision.id(), revision.articleId(), revision.revisionNo(),
                                revision.title(), revision.summary(), revision.bodyMarkdown(),
                                revision.bodyPlain(), revision.cover(), revision.tagsJson(),
                                revision.contentHash(), revision.createdBy()))
                        .toList();
        List<StageBRevisionAwareStateValidator.JobState> jobs = loadJobs(article.id()).stream()
                .map(job -> new StageBRevisionAwareStateValidator.JobState(
                        job.id(), job.articleId(), job.revisionId(), job.contentHash(), job.state(),
                        job.modelDecision(), job.riskScore(), job.policyHitsJson(), job.attemptCount(),
                        job.nextAttemptAt(), job.leaseOwner(), job.leaseUntil(), job.lastError(),
                        job.reviewerId(), job.reviewReason(), job.reviewedAt(), job.lockVersion()))
                .toList();
        List<StageBRevisionAwareStateValidator.AttemptState> attempts =
                loadAttempts(article.id()).stream()
                        .map(attempt -> new StageBRevisionAwareStateValidator.AttemptState(
                                attempt.id(), attempt.jobId(), attempt.attemptNo(),
                                attempt.provider(), attempt.model(), attempt.promptVersion(),
                                attempt.inputHash(), attempt.structuredOutputJson(),
                                attempt.latencyMs(), attempt.tokenUsageJson(),
                                attempt.finishReason(), attempt.errorCode(), attempt.createdAt()))
                        .toList();
        return revisionAwareValidator.validate(
                new StageBRevisionAwareStateValidator.ArticleState(
                        article.id(), article.authorId(), article.status(), article.isDeleted(),
                        article.latestRevisionId(), article.pendingRevisionId(),
                        article.publishedRevisionId(), article.visibilityState(), article.reviewState()),
                draftState, revisions, jobs, attempts, legacy);
    }

    private void verifyStatusZero(VerifierArticle article, MismatchCollector mismatches) {
        if (article.latestRevisionId() != null || article.pendingRevisionId() != null
                || article.publishedRevisionId() != null) {
            mismatches.add("STATUS_ZERO_POINTER_MISMATCH", article.id(),
                    "status 0 baseline must remain pointer-free");
        }
    }

    private void verifyPublished(VerifierArticle article, ArticleContentSnapshot legacy,
                                 MismatchCollector mismatches) {
        if (article.publishedRevisionId() == null
                || !Objects.equals(article.latestRevisionId(), article.publishedRevisionId())
                || article.pendingRevisionId() != null) {
            mismatches.add("PUBLISHED_POINTER_MISMATCH", article.id(),
                    "status 1 requires latest=published and no pending pointer");
            return;
        }
        StoredRevision revision = loadRevision(article.publishedRevisionId());
        if (!revisionMatches(article, revision, legacy)) {
            mismatches.add("PUBLISHED_REVISION_MISMATCH", article.id(),
                    "published revision does not match the public legacy mirror");
            return;
        }
    }

    private void verifyPending(VerifierArticle article, ArticleContentSnapshot legacy,
                               MismatchCollector mismatches) {
        if (article.pendingRevisionId() == null
                || !Objects.equals(article.latestRevisionId(), article.pendingRevisionId())
                || article.publishedRevisionId() != null) {
            mismatches.add("PENDING_POINTER_MISMATCH", article.id(),
                    "status 2 requires latest=pending and no published pointer");
            return;
        }
        StoredRevision revision = loadRevision(article.pendingRevisionId());
        if (!revisionMatches(article, revision, legacy)) {
            mismatches.add("PENDING_REVISION_MISMATCH", article.id(),
                    "pending revision does not match the frozen legacy snapshot");
            return;
        }
        List<StoredJob> jobs = loadJobs(article.id()).stream()
                .filter(job -> job.revisionId() == revision.id())
                .toList();
        if (jobs.size() != 1) {
            mismatches.add("MODERATION_JOB_COUNT_MISMATCH", article.id(),
                    "pending revision must have exactly one bound job");
        } else {
            StoredJob job = jobs.getFirst();
            if (!revision.contentHash().equals(job.contentHash())) {
                mismatches.add("MODERATION_JOB_HASH_MISMATCH", article.id(),
                        "moderation job hash differs from its revision");
            }
            if (!"HUMAN_PENDING".equals(job.state())) {
                mismatches.add("MODERATION_JOB_STATE_MISMATCH", article.id(),
                        "legacy pending job is not HUMAN_PENDING");
            }
            if (!isSupportedLegacyPendingReason(job.lastError())) {
                mismatches.add("MODERATION_JOB_REASON_MISMATCH", article.id(),
                        "legacy pending job reason is not the migration marker");
            }
            if (!job.hasUntouchedLegacyFields()) {
                mismatches.add("MODERATION_JOB_FROZEN_FIELDS_MISMATCH", article.id(),
                        "legacy pending job contains provider, lease, review or version state");
            }
        }
    }

    private boolean isSupportedLegacyPendingReason(String reason) {
        return LEGACY_BACKFILL_MANUAL.equals(reason) || LEGACY_SHADOW_MANUAL.equals(reason);
    }

    private void verifyRejected(VerifierArticle article, ArticleContentSnapshot legacy,
                                MismatchCollector mismatches) {
        if (article.latestRevisionId() == null || article.pendingRevisionId() != null
                || article.publishedRevisionId() != null) {
            mismatches.add("REJECTED_POINTER_MISMATCH", article.id(),
                    "status 3 requires only a latest pointer");
            return;
        }
        StoredRevision revision = loadRevision(article.latestRevisionId());
        if (!revisionMatches(article, revision, legacy)) {
            mismatches.add("REJECTED_REVISION_MISMATCH", article.id(),
                    "rejected revision does not match the frozen legacy snapshot");
        }
    }

    private void verifyEveryRevisionHash(int pageSize, MismatchCollector mismatches) {
        long cursor = 0;
        while (true) {
            List<StoredRevision> revisions = jdbc.query("""
                    SELECT id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,
                           tags_json,content_hash,created_by FROM article_revision
                    WHERE id>? ORDER BY id LIMIT ?
                    """, (rs, rowNum) -> revision(rs), cursor, pageSize);
            if (revisions.isEmpty()) {
                return;
            }
            for (StoredRevision revision : revisions) {
                cursor = revision.id();
                ArticleContentSnapshot computed = canonicalizeStored(revision.title(), revision.summary(),
                        revision.bodyMarkdown(), revision.cover(), revision.tagsJson());
                if (computed == null || !computed.contentHash().equals(revision.contentHash())
                        || !Objects.equals(computed.bodyPlain(), revision.bodyPlain())) {
                    mismatches.add("REVISION_SELF_HASH_MISMATCH", revision.articleId(),
                            "revisionId=" + revision.id());
                }
            }
            if (revisions.size() < pageSize) {
                return;
            }
        }
    }

    private ElasticsearchScan verifyElasticsearch(long expectedDocumentCount,
                                                  int pageSize,
                                                  MismatchCollector mismatches) {
        long documentCount = 0;
        long presentExpectedDocumentCount = 0;
        int pages = 0;
        int maximumLookupBatchSize = 0;
        StageBElasticsearchPitCursor pitCursor = null;
        try {
            var aliases = elasticsearch.indices().getAlias(request ->
                    request.name(properties.getElasticsearchReadAlias()));
            if (aliases.result().size() != 1) {
                mismatches.add("ELASTICSEARCH_ALIAS_TARGET_COUNT", null,
                        "aliasTargetCount=" + aliases.result().size());
                if (expectedDocumentCount != 0) {
                    mismatches.add("ELASTICSEARCH_DOCUMENT_MISSING", null,
                            "missingDocumentCount=" + expectedDocumentCount);
                }
                return new ElasticsearchScan(0, 0, 0);
            }
            String keepAlive = keepAlive();
            pitCursor = new StageBElasticsearchPitCursor(openPointInTime(keepAlive));
            List<FieldValue> searchAfter = List.of();
            while (true) {
                String activePitId = pitCursor.current();
                SearchRequest.Builder request = new SearchRequest.Builder()
                        .pit(pit -> pit.id(activePitId).keepAlive(time -> time.time(keepAlive)))
                        .size(pageSize)
                        .query(query -> query.bool(bool -> bool.mustNot(mustNot ->
                                mustNot.term(term -> term.field("projectionTombstone").value(true)))))
                        .sort(sort -> sort.field(field -> field.field("_shard_doc").order(SortOrder.Asc)));
                if (!searchAfter.isEmpty()) {
                    request.searchAfter(searchAfter);
                }
                SearchResponse<Map> response = elasticsearch.search(request.build(), Map.class);
                pitCursor.advance(response.pitId());
                if (response.timedOut() || response.shards().failed().longValue() != 0) {
                    mismatches.add("ELASTICSEARCH_PARTIAL_RESULT", null,
                            "timedOut=" + response.timedOut() + ",failedShards="
                                    + response.shards().failed());
                    break;
                }
                List<Hit<Map>> hits = response.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                pages++;
                LinkedHashMap<Long, Hit<Map>> pageHits = new LinkedHashMap<>();
                for (Hit<Map> hit : hits) {
                    documentCount++;
                    long articleId;
                    try {
                        articleId = Long.parseLong(hit.id());
                    } catch (NumberFormatException exception) {
                        mismatches.add("ELASTICSEARCH_DOCUMENT_ID_INVALID", null,
                                "document id is not a long");
                        continue;
                    }
                    if (pageHits.putIfAbsent(articleId, hit) != null) {
                        mismatches.add("ELASTICSEARCH_DOCUMENT_DUPLICATE", articleId,
                                "duplicate id within one PIT page");
                    }
                }
                maximumLookupBatchSize = Math.max(maximumLookupBatchSize, pageHits.size());
                Map<Long, ExpectedDocument> expectedPage = loadExpectedDocuments(pageHits.keySet());
                for (Map.Entry<Long, Hit<Map>> entry : pageHits.entrySet()) {
                    ExpectedDocument expectedDocument = expectedPage.get(entry.getKey());
                    if (expectedDocument == null) {
                        mismatches.add("ELASTICSEARCH_DOCUMENT_EXTRA", entry.getKey(),
                                "document has no current public MySQL pointer");
                    } else {
                        presentExpectedDocumentCount++;
                        if (!expectedDocument.matches(entry.getValue().source())) {
                            mismatches.add("ELASTICSEARCH_DOCUMENT_MISMATCH", entry.getKey(),
                                    "document fields differ from current published revision/article");
                        }
                    }
                }
                searchAfter = hits.getLast().sort();
                if (hits.size() < pageSize) {
                    break;
                }
            }
        } catch (Exception exception) {
            mismatches.add("ELASTICSEARCH_VERIFY_FAILED", null, safeException(exception));
        } finally {
            if (pitCursor != null) {
                try {
                    String closingPit = pitCursor.current();
                    if (!closePointInTime(closingPit)) {
                        mismatches.add("ELASTICSEARCH_PIT_CLOSE_FAILED", null,
                                "PIT close returned succeeded=false");
                    }
                } catch (Exception exception) {
                    mismatches.add("ELASTICSEARCH_PIT_CLOSE_FAILED", null,
                            exception.getClass().getSimpleName());
                }
            }
        }
        if (documentCount != expectedDocumentCount) {
            mismatches.add("ELASTICSEARCH_DOCUMENT_COUNT_MISMATCH", null,
                    "expected=" + expectedDocumentCount + ",actual=" + documentCount);
        }
        long missingCount = Math.max(0, expectedDocumentCount - presentExpectedDocumentCount);
        if (missingCount != 0) {
            mismatches.add("ELASTICSEARCH_DOCUMENT_MISSING", null,
                    "missingDocumentCount=" + missingCount);
        }
        return new ElasticsearchScan(documentCount, pages, maximumLookupBatchSize);
    }

    private Map<Long, ExpectedDocument> loadExpectedDocuments(Set<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(articleIds.size(), "?"));
        List<ExpectedDocument> documents = jdbc.query("""
                SELECT a.id,a.author_id,a.view_count,a.like_count,a.comment_count,a.collect_count,
                       a.create_time,r.id AS revision_id,r.content_hash,
                       r.title,r.body_markdown,r.summary,r.cover
                FROM article a
                JOIN article_revision r ON r.id=a.published_revision_id AND r.article_id=a.id
                WHERE a.status=1 AND a.is_deleted=0 AND a.id IN (
                """ + placeholders + ")", (rs, rowNum) -> new ExpectedDocument(
                rs.getLong("id"), rs.getLong("revision_id"), rs.getString("content_hash"),
                rs.getString("title"), rs.getString("body_markdown"),
                rs.getString("summary"), rs.getString("cover"), rs.getLong("author_id"),
                rs.getInt("view_count"), rs.getInt("like_count"), rs.getInt("comment_count"),
                rs.getInt("collect_count"), rs.getTimestamp("create_time").toLocalDateTime()),
                articleIds.toArray());
        LinkedHashMap<Long, ExpectedDocument> byId = new LinkedHashMap<>(documents.size());
        for (ExpectedDocument document : documents) {
            byId.put(document.id(), document);
        }
        return java.util.Collections.unmodifiableMap(byId);
    }

    private String openPointInTime(String keepAlive) throws Exception {
        String alias = exactAlias();
        Request request = new Request("POST", "/" + alias + "/_pit");
        request.addParameter("keep_alive", keepAlive);
        Response response = elasticsearchRestClient.performRequest(request);
        String body = EntityUtils.toString(response.getEntity());
        String pitId = objectMapper.readTree(body).path("id").asText();
        if (pitId.isBlank()) {
            throw new IllegalStateException("Elasticsearch PIT open returned no id");
        }
        return pitId;
    }

    private boolean closePointInTime(String pitId) throws Exception {
        Request request = new Request("DELETE", "/_pit");
        request.setJsonEntity(objectMapper.createObjectNode().put("id", pitId).toString());
        Response response = elasticsearchRestClient.performRequest(request);
        return objectMapper.readTree(EntityUtils.toString(response.getEntity()))
                .path("succeeded").asBoolean(false);
    }

    private String exactAlias() {
        String alias = properties.getElasticsearchReadAlias();
        if (alias == null || !alias.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalStateException("Elasticsearch verification requires one exact alias name");
        }
        return alias;
    }

    private long countWindowWrites(LocalDateTime start, LocalDateTime end) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM article WHERE update_time>? AND update_time<=?) +
                  (SELECT COUNT(*) FROM article_draft WHERE updated_at>? AND updated_at<=?) +
                  (SELECT COUNT(*) FROM article_revision WHERE created_at>? AND created_at<=?) +
                  (SELECT COUNT(*) FROM article_moderation_job WHERE updated_at>? AND updated_at<=?)
                """, Long.class, start, end, start, end, start, end, start, end);
    }

    private StoredDraft loadDraft(long articleId) {
        List<StoredDraft> rows = jdbc.query("""
                SELECT article_id,user_id,title,summary,body_markdown,body_plain,cover,tags_json,content_hash
                FROM article_draft WHERE article_id=?
                """, (rs, rowNum) -> new StoredDraft(
                rs.getLong("article_id"), rs.getLong("user_id"), rs.getString("title"),
                rs.getString("summary"), rs.getString("body_markdown"), rs.getString("body_plain"),
                rs.getString("cover"), rs.getString("tags_json"), rs.getString("content_hash")), articleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredRevision loadRevisionOne(long articleId) {
        List<StoredRevision> rows = jdbc.query("""
                SELECT id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,
                       tags_json,content_hash,created_by FROM article_revision
                WHERE article_id=? AND revision_no=1
                """, (rs, rowNum) -> revision(rs), articleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<StoredRevision> loadRevisions(long articleId) {
        return jdbc.query("""
                SELECT id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,
                       tags_json,content_hash,created_by FROM article_revision
                WHERE article_id=? ORDER BY revision_no,id
                """, (rs, rowNum) -> revision(rs), articleId);
    }

    private List<StoredJob> loadJobs(long articleId) {
        return jdbc.query("""
                SELECT id,article_id,revision_id,content_hash,state,model_decision,risk_score,
                       policy_hits_json,attempt_count,next_attempt_at,lease_owner,lease_until,
                       last_error,reviewer_id,review_reason,reviewed_at,lock_version
                FROM article_moderation_job WHERE article_id=? ORDER BY id
                """, (rs, rowNum) -> new StoredJob(
                rs.getLong("id"), rs.getLong("article_id"), rs.getLong("revision_id"),
                rs.getString("content_hash"), rs.getString("state"),
                rs.getString("model_decision"), rs.getString("risk_score"),
                rs.getString("policy_hits_json"), rs.getInt("attempt_count"),
                rs.getTimestamp("next_attempt_at"), rs.getString("lease_owner"),
                rs.getTimestamp("lease_until"), rs.getString("last_error"),
                nullableLong(rs, "reviewer_id"), rs.getString("review_reason"),
                rs.getTimestamp("reviewed_at"), rs.getLong("lock_version")), articleId);
    }

    private List<StoredAttempt> loadAttempts(long articleId) {
        return jdbc.query("""
                SELECT ma.id,ma.job_id,ma.attempt_no,ma.provider,ma.model,ma.prompt_version,
                       ma.input_hash,ma.structured_output_json,ma.latency_ms,ma.token_usage_json,
                       ma.finish_reason,ma.error_code,ma.created_at
                FROM article_moderation_attempt ma
                JOIN article_moderation_job mj ON mj.id=ma.job_id
                WHERE mj.article_id=?
                ORDER BY ma.id
                """, (rs, rowNum) -> new StoredAttempt(
                rs.getLong("id"), rs.getLong("job_id"), rs.getInt("attempt_no"),
                rs.getString("provider"), rs.getString("model"),
                rs.getString("prompt_version"), rs.getString("input_hash"),
                rs.getString("structured_output_json"), rs.getLong("latency_ms"),
                rs.getString("token_usage_json"), rs.getString("finish_reason"),
                rs.getString("error_code"), rs.getTimestamp("created_at")), articleId);
    }

    private StoredRevision loadRevision(long revisionId) {
        List<StoredRevision> rows = jdbc.query("""
                SELECT id,article_id,revision_no,title,summary,body_markdown,body_plain,cover,
                       tags_json,content_hash,created_by FROM article_revision WHERE id=?
                """, (rs, rowNum) -> revision(rs), revisionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean revisionMatches(VerifierArticle article, StoredRevision revision,
                                    ArticleContentSnapshot legacy) {
        if (revision == null || revision.articleId() != article.id()
                || revision.createdBy() != article.authorId()) {
            return false;
        }
        ArticleContentSnapshot snapshot = canonicalizeStored(revision.title(), revision.summary(),
                revision.bodyMarkdown(), revision.cover(), revision.tagsJson());
        return snapshot != null && snapshot.contentHash().equals(revision.contentHash())
                && snapshot.contentHash().equals(legacy.contentHash())
                && Objects.equals(snapshot.bodyPlain(), revision.bodyPlain());
    }

    private ArticleContentSnapshot canonicalizeStored(String title, String summary, String body,
                                                       String cover, String tagsJson) {
        try {
            List<String> tags = objectMapper.readerForListOf(String.class).readValue(tagsJson);
            return canonicalizer.canonicalize(title, summary, body, cover, tags);
        } catch (Exception exception) {
            return null;
        }
    }

    private VerifierArticle article(ResultSet rs) throws SQLException {
        return new VerifierArticle(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                rs.getString("content"), rs.getString("cover"), rs.getLong("author_id"),
                rs.getInt("view_count"), rs.getInt("like_count"), rs.getInt("comment_count"),
                rs.getInt("collect_count"), rs.getTimestamp("create_time").toLocalDateTime(),
                nullableInteger(rs, "status"), nullableInteger(rs, "is_deleted"),
                nullableLong(rs, "latest_revision_id"), nullableLong(rs, "pending_revision_id"),
                nullableLong(rs, "published_revision_id"), rs.getString("visibility_state"),
                rs.getString("review_state"));
    }

    private StoredRevision revision(ResultSet rs) throws SQLException {
        return new StoredRevision(rs.getLong("id"), rs.getLong("article_id"),
                rs.getLong("revision_no"), rs.getString("title"), rs.getString("summary"),
                rs.getString("body_markdown"), rs.getString("body_plain"), rs.getString("cover"),
                rs.getString("tags_json"), rs.getString("content_hash"), rs.getLong("created_by"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private LocalDateTime databaseNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        if (now == null) {
            throw new IllegalStateException("database clock returned null");
        }
        return now.toLocalDateTime();
    }

    private int verifiedPageSize() {
        int size = properties.getVerificationPageSize();
        if (size < 1 || size > 1_000) {
            throw new IllegalStateException("verification page size must be between 1 and 1000");
        }
        return size;
    }

    private String keepAlive() {
        long seconds = properties.getElasticsearchPitKeepAlive().toSeconds();
        if (seconds < 10 || seconds > 600) {
            throw new IllegalStateException("Elasticsearch PIT keep-alive must be between 10 and 600 seconds");
        }
        return seconds + "s";
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String safeException(Exception exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName()
                + (message == null ? "" : ": " + message.replaceAll("[\\r\\n]+", " "));
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private record VerificationScan(int pages) {
    }

    private record ElasticsearchScan(long documentCount, int pages, int maximumLookupBatchSize) {
    }

    private record VerifierArticle(
            long id, String title, String summary, String content, String cover, long authorId,
            int viewCount, int likeCount, int commentCount, int collectCount, LocalDateTime createTime,
            Integer status, Integer isDeleted, Long latestRevisionId, Long pendingRevisionId,
            Long publishedRevisionId, String visibilityState, String reviewState) {
    }

    private record StoredDraft(
            long articleId, long userId, String title, String summary, String bodyMarkdown,
            String bodyPlain, String cover, String tagsJson, String contentHash) {
    }

    private record StoredRevision(
            long id, long articleId, long revisionNo, String title, String summary,
            String bodyMarkdown, String bodyPlain, String cover, String tagsJson,
            String contentHash, long createdBy) {
    }

    private record StoredJob(
            long id, long articleId, long revisionId, String contentHash, String state, String modelDecision,
            String riskScore, String policyHitsJson, int attemptCount, Timestamp nextAttemptAt,
            String leaseOwner, Timestamp leaseUntil, String lastError, Long reviewerId,
            String reviewReason, Timestamp reviewedAt, long lockVersion) {

        boolean hasUntouchedLegacyFields() {
            return modelDecision == null && riskScore == null && policyHitsJson == null
                    && attemptCount == 0 && nextAttemptAt == null && leaseOwner == null
                    && leaseUntil == null && reviewerId == null && reviewReason == null
                    && reviewedAt == null && lockVersion == 0;
        }
    }

    private record StoredAttempt(
            long id, long jobId, int attemptNo, String provider, String model,
            String promptVersion, String inputHash, String structuredOutputJson,
            long latencyMs, String tokenUsageJson, String finishReason,
            String errorCode, Timestamp createdAt) {
    }

    private record ExpectedDocument(
            long id, long revisionId, String contentHash,
            String title, String content, String summary, String cover, long authorId,
            int viewCount, int likeCount, int commentCount, int collectCount,
            LocalDateTime createTime) {

        boolean matches(Map source) {
            if (source == null) {
                return false;
            }
            return numberEquals(revisionId, source.get("revisionId"))
                    && Objects.equals(contentHash, source.get("contentHash"))
                    && Objects.equals(title, source.get("title"))
                    && Objects.equals(content, source.get("content"))
                    && Objects.equals(summary, source.get("summary"))
                    && Objects.equals(cover, source.get("cover"))
                    && numberEquals(authorId, source.get("authorId"))
                    && numberEquals(viewCount, source.get("viewCount"))
                    && numberEquals(likeCount, source.get("likeCount"))
                    && numberEquals(commentCount, source.get("commentCount"))
                    && numberEquals(collectCount, source.get("collectCount"))
                    && dateEquals(createTime, source.get("createTime"));
        }

        private static boolean numberEquals(long expected, Object actual) {
            return actual instanceof Number number && number.longValue() == expected;
        }

        private static boolean dateEquals(LocalDateTime expected, Object actual) {
            if (actual == null) {
                return false;
            }
            try {
                return expected.withNano(0).equals(LocalDateTime.parse(String.valueOf(actual), ES_DATE));
            } catch (DateTimeParseException exception) {
                return false;
            }
        }
    }

    private static final class MismatchCollector {
        private final int maximum;
        private final List<StageBMigrationMismatch> items = new ArrayList<>();
        private long count;

        private MismatchCollector(int maximum) {
            if (maximum < 1 || maximum > 10_000) {
                throw new IllegalStateException("maximum reported migration mismatches must be 1..10000");
            }
            this.maximum = maximum;
        }

        void add(String code, Long articleId, String detail) {
            count++;
            if (items.size() < maximum) {
                items.add(new StageBMigrationMismatch(code, articleId, detail));
            }
        }

        long count() {
            return count;
        }

        List<StageBMigrationMismatch> items() {
            return List.copyOf(items);
        }
    }
}
