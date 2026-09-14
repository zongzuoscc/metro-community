package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchHit;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchRepository;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorHit;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorRepository;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridArticleRetrievalServiceTest {

    private final ArticleChunkSearchRepository lexical = mock(ArticleChunkSearchRepository.class);
    private final ArticleVectorRepository vectors = mock(ArticleVectorRepository.class);
    private final PublishedArticleChunkResolver resolver = mock(PublishedArticleChunkResolver.class);
    private final EmbeddingGateway embedding = command -> new EmbeddingResult(
            List.of(new float[]{1F, 0F}), "test", "bge-m3");

    HybridArticleRetrievalServiceTest() {
        when(resolver.activeParserGeneration()).thenReturn(3L);
    }

    @Test
    void cancellationFromAnArticleDependencyMustNotBecomeSuccessfulFallback() {
        when(lexical.searchActive("cancelled", 40)).thenThrow(
                new java.util.concurrent.CancellationException("run revoked"));

        assertThatThrownBy(() -> service().retrieve(new ArticleRetrievalQuery(
                7L, "cancelled", "cancelled", Instant.parse("2026-08-12T00:00:30Z"))))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void hydeUsesTheFrozenTurnRouteInsteadOfReloadingTheMutableRouter() {
        List<AiCapability> routed = new CopyOnWriteArrayList<>();
        PreparedUserAiChat frozen = new PreparedUserAiChat("frozen-model", UserAiFundingSource.USER,
                command -> {
                    routed.add(command.capability());
                    return new UserAiRoutedResult(new AiChatResult("冻结路由生成的检索文档", "stop",
                            10, 10, "user", "frozen-model"), UserAiFundingSource.USER);
                });
        RecordingExecutor executor = new RecordingExecutor();

        guardedService(executor, embedding).retrieve(new ArticleRetrievalQuery(7L, "frozen", "锁",
                Instant.parse("2026-08-12T00:00:30Z"), frozen, () -> { }));

        assertThat(routed).containsExactly(AiCapability.HYDE);
        assertThat(executor.capabilities()).containsExactly(
                AiCapability.EMBEDDING, AiCapability.HYDE, AiCapability.EMBEDDING);
    }

    @Test
    void revokedDuringLexicalLookupCannotStartEmbedding() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean embedded = new AtomicBoolean();
        when(lexical.searchActive("锁", 40)).thenAnswer(invocation -> {
            active.set(false);
            return List.of();
        });

        assertThatThrownBy(() -> guardedService(new RecordingExecutor(), command -> {
            embedded.set(true);
            return embedding.embed(command);
        }).retrieve(guardedQuery(active, harmlessRoute())))
                .isInstanceOf(java.util.concurrent.CancellationException.class);

        assertThat(embedded).isFalse();
    }

    @Test
    void revokedWhileEmbeddingIsQueuedCannotSendTheQueryToTheProvider() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean embedded = new AtomicBoolean();
        RecordingExecutor executor = new RecordingExecutor();
        executor.beforeOperation = capability -> active.set(false);

        assertThatThrownBy(() -> guardedService(executor, command -> {
            embedded.set(true);
            return embedding.embed(command);
        }).retrieve(guardedQuery(active, harmlessRoute())))
                .isInstanceOf(java.util.concurrent.CancellationException.class);

        assertThat(embedded).isFalse();
    }

    @Test
    void revokedWhileHydeIsQueuedCannotStartTheFrozenModel() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean generated = new AtomicBoolean();
        RecordingExecutor executor = new RecordingExecutor();
        executor.beforeOperation = capability -> {
            if (capability == AiCapability.HYDE) active.set(false);
        };
        PreparedUserAiChat route = new PreparedUserAiChat("frozen-model", UserAiFundingSource.USER,
                command -> {
                    generated.set(true);
                    return harmlessRoute().generate(command);
                });

        assertThatThrownBy(() -> guardedService(executor, embedding).retrieve(guardedQuery(active, route)))
                .isInstanceOf(java.util.concurrent.CancellationException.class);

        assertThat(generated).isFalse();
    }

    @Test
    void revokedDuringHydeDoesNotStartTheSecondEmbedding() {
        AtomicBoolean active = new AtomicBoolean(true);
        List<String> embedded = new CopyOnWriteArrayList<>();
        PreparedUserAiChat route = new PreparedUserAiChat("frozen-model", UserAiFundingSource.USER,
                command -> {
                    active.set(false);
                    return harmlessRoute().generate(command);
                });

        assertThatThrownBy(() -> guardedService(new RecordingExecutor(), command -> {
            embedded.add(command.inputs().getFirst());
            return embedding.embed(command);
        }).retrieve(guardedQuery(active, route)))
                .isInstanceOf(java.util.concurrent.CancellationException.class);

        assertThat(embedded).containsExactly("锁");
    }

    private ArticleRetrievalQuery guardedQuery(AtomicBoolean active, PreparedUserAiChat route) {
        return new ArticleRetrievalQuery(7L, "guarded", "锁", Instant.parse("2026-08-12T00:00:30Z"),
                route, () -> {
                    if (!active.get()) throw new java.util.concurrent.CancellationException("run or memory revoked");
                });
    }

    private PreparedUserAiChat harmlessRoute() {
        return new PreparedUserAiChat("frozen-model", UserAiFundingSource.USER,
                command -> new UserAiRoutedResult(new AiChatResult("冻结路由生成的检索文档", "stop",
                        10, 10, "user", "frozen-model"), UserAiFundingSource.USER));
    }

    private HybridArticleRetrievalService guardedService(RecordingExecutor executor,
                                                          EmbeddingGateway gateway) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
        HydeHypotheticalDocumentService hyde = new HydeHypotheticalDocumentService(executor,
                (userId, command) -> { throw new AssertionError("Mutable router must not be consulted"); },
                clock, Duration.ofSeconds(8), 600);
        return new HybridArticleRetrievalService(lexical, vectors, resolver, executor, gateway,
                clock, "metro_article_chunks_read", "bge-m3", 40, 8, Duration.ofSeconds(20), hyde, 3);
    }

    @Test
    void fusesBothRankingsThenDropsStaleCandidatesThroughMysql() {
        when(lexical.searchActive("java concurrency", 40)).thenReturn(List.of(
                new ArticleChunkSearchHit(11L, 101L, 1001L, 9F),
                new ArticleChunkSearchHit(12L, 102L, 1002L, 8F)));
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of(
                new ArticleVectorHit(12L, 102L, 1002L, .99F),
                new ArticleVectorHit(13L, 103L, 1003L, .98F)));
        ResolvedArticleChunk current = new ResolvedArticleChunk(12L, 102L, 1002L, 0,
                "Locks", List.of("Concurrency"), "Use a bounded critical section.",
                "a".repeat(64), "b".repeat(64));
        when(resolver.resolveCurrent(List.of(11L, 12L, 13L))).thenReturn(List.of(current));

        HybridArticleRetrievalService service = service();

        ArticleRetrievalResult result = service.retrieve(
                new ArticleRetrievalQuery(7L, "req-1", "java concurrency",
                        Instant.parse("2026-08-12T00:00:30Z")));

        assertThat(result.lexicalCount()).isEqualTo(2);
        assertThat(result.denseCount()).isEqualTo(2);
        assertThat(result.authorizedChunks()).containsExactly(current);
        assertThat(result.rankedCandidates()).extracting(RankedArticleChunk::chunkId)
                .containsExactly(12L);
    }

    @Test
    void denseFailureFallsBackToAuthorizedBm25Candidates() {
        when(lexical.searchActive("mysql lock", 40)).thenReturn(List.of(
                new ArticleChunkSearchHit(21L, 201L, 2001L, 9F)));
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenThrow(new IllegalStateException("milvus down"));
        ResolvedArticleChunk current = new ResolvedArticleChunk(21L, 201L, 2001L, 0,
                "MySQL", List.of(), "Use SELECT FOR UPDATE.",
                "c".repeat(64), "d".repeat(64));
        when(resolver.resolveCurrent(List.of(21L))).thenReturn(List.of(current));

        ArticleRetrievalResult result = service().retrieve(
                new ArticleRetrievalQuery(8L, "req-2", "mysql lock",
                        Instant.parse("2026-08-12T00:00:30Z")));

        assertThat(result.denseAvailable()).isFalse();
        assertThat(result.authorizedChunks()).containsExactly(current);
    }

    @Test
    void lowRecallUsesOneHypotheticalDocumentAsAThirdVectorRanking() {
        List<String> embeddedTexts = new CopyOnWriteArrayList<>();
        EmbeddingGateway recordingEmbedding = command -> {
            String text = command.inputs().getFirst();
            embeddedTexts.add(text);
            float[] vector = text.equals("如何让锁更安全")
                    ? new float[]{1F, 0F} : new float[]{0F, 1F};
            return new EmbeddingResult(List.of(vector), "test", "bge-m3");
        };
        when(lexical.searchActive("如何让锁更安全", 40)).thenReturn(List.of());
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        when(vectors.searchActive("metro_article_chunks_read", new float[]{0F, 1F},
                40, "bge-m3", 3L)).thenReturn(List.of(
                new ArticleVectorHit(41L, 401L, 4001L, .97F)));
        ResolvedArticleChunk hydeOnly = new ResolvedArticleChunk(41L, 401L, 4001L, 0,
                "临界区与锁", List.of("并发"), "缩小临界区并使用有界等待。",
                "e".repeat(64), "f".repeat(64));
        when(resolver.resolveCurrent(List.of())).thenReturn(List.of());
        when(resolver.resolveCurrent(List.of(41L))).thenReturn(List.of(hydeOnly));
        RecordingExecutor executor = new RecordingExecutor();
        HydeHypotheticalDocumentService hyde = new HydeHypotheticalDocumentService(
                executor,
                (userId, command) -> {
                    String prompt = command.messages().stream()
                            .map(message -> message.text()).reduce("", String::concat);
                    assertThat(prompt).contains("如何让锁更安全")
                            .contains("600")
                            .doesNotContain("缩小临界区")
                            .doesNotContain("个人记忆");
                    return new UserAiRoutedResult(new AiChatResult(
                            "在高并发系统中，应缩小锁保护的临界区，并使用超时与有界等待。",
                            "stop", 20, 30, "test", "chat-test"), UserAiFundingSource.PLATFORM);
                },
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(8), 600);
        HybridArticleRetrievalService service = new HybridArticleRetrievalService(
                lexical, vectors, resolver, executor, recordingEmbedding,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "metro_article_chunks_read", "bge-m3", 40, 8, Duration.ofSeconds(20),
                hyde, 3);

        ArticleRetrievalResult result = service.retrieve(new ArticleRetrievalQuery(
                9L, "req-hyde", "如何让锁更安全", Instant.parse("2026-08-12T00:00:30Z")));

        assertThat(result.authorizedChunks()).containsExactly(hydeOnly);
        assertThat(result.rankedCandidates().getFirst().hydeRank()).isEqualTo(1);
        assertThat(embeddedTexts).containsExactly("如何让锁更安全",
                "在高并发系统中，应缩小锁保护的临界区，并使用超时与有界等待。");
        assertThat(executor.capabilities()).containsExactly(
                AiCapability.EMBEDDING, AiCapability.HYDE, AiCapability.EMBEDDING);
        verify(resolver).resolveCurrent(List.of(41L));
    }

    @Test
    void malformedHypotheticalDocumentFallsBackToTheCompletedFirstRound() {
        when(lexical.searchActive("锁", 40)).thenReturn(List.of(
                new ArticleChunkSearchHit(51L, 501L, 5001L, 9F)));
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        ResolvedArticleChunk lexicalOnly = new ResolvedArticleChunk(51L, 501L, 5001L, 0,
                "锁的基础", List.of(), "临界区需要受到保护。",
                "1".repeat(64), "2".repeat(64));
        when(resolver.resolveCurrent(List.of(51L))).thenReturn(List.of(lexicalOnly));
        RecordingExecutor executor = new RecordingExecutor();
        HydeHypotheticalDocumentService hyde = new HydeHypotheticalDocumentService(
                executor,
                (userId, command) -> new UserAiRoutedResult(
                        new AiChatResult("抱歉，我无法生成这项内容。", "content_filter",
                                5, 8, "test", "chat-test"),
                        UserAiFundingSource.PLATFORM),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(8), 600);
        HybridArticleRetrievalService service = new HybridArticleRetrievalService(
                lexical, vectors, resolver, executor, embedding,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "metro_article_chunks_read", "bge-m3", 40, 8, Duration.ofSeconds(20),
                hyde, 3);

        ArticleRetrievalResult result = service.retrieve(new ArticleRetrievalQuery(
                10L, "req-hyde-fallback", "锁", Instant.parse("2026-08-12T00:00:30Z")));

        assertThat(result.authorizedChunks()).containsExactly(lexicalOnly);
        assertThat(result.rankedCandidates().getFirst().hydeRank()).isNull();
        assertThat(executor.capabilities()).containsExactly(AiCapability.EMBEDDING, AiCapability.HYDE);
    }

    @Test
    void officialHydeTemplateTreatsBracesInTheQuestionAsPlainText() {
        String question = "Map<String, {value}> 怎么加锁";
        HydeHypotheticalDocumentService hyde = new HydeHypotheticalDocumentService(
                new RecordingExecutor(),
                (userId, command) -> {
                    assertThat(command.messages()).singleElement().satisfies(message ->
                            assertThat(message.text()).contains(question));
                    return new UserAiRoutedResult(new AiChatResult("使用按键粒度的锁。", "stop",
                            8, 9, "test", "chat-test"), UserAiFundingSource.PLATFORM);
                },
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(8), 600);

        assertThat(hyde.generate(7L, "braces", question,
                Instant.parse("2026-08-12T00:00:30Z"))).isEqualTo("使用按键粒度的锁。");
    }

    @Test
    void emptyHydeOutputIsNotEmbeddedAsTheOriginalQuestion() {
        assertInvalidHydeDoesNotStartSecondEmbedding(
                new AiChatResult("   ", "stop", 3, 0, "test", "chat-test"));
    }

    @Test
    void overlongHydeOutputDoesNotStartSecondEmbedding() {
        assertInvalidHydeDoesNotStartSecondEmbedding(
                new AiChatResult("x".repeat(601), "stop", 3, 601, "test", "chat-test"));
    }

    @Test
    void exceptionalHydeCallFallsBackWithoutStartingSecondEmbedding() {
        List<String> embedded = new CopyOnWriteArrayList<>();
        when(lexical.searchActive("锁", 40)).thenReturn(List.of());
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        when(resolver.resolveCurrent(List.of())).thenReturn(List.of());
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER,
                command -> { throw new IllegalStateException("provider unavailable"); });

        ArticleRetrievalResult result = guardedService(new RecordingExecutor(), command -> {
            embedded.add(command.inputs().getFirst());
            return embedding.embed(command);
        }).retrieve(new ArticleRetrievalQuery(7L, "exceptional-hyde", "锁",
                Instant.parse("2026-08-12T00:00:30Z"), route, () -> { }));

        assertThat(result.authorizedChunks()).isEmpty();
        assertThat(embedded).containsExactly("锁");
    }

    @Test
    void longQuestionWithTooFewPublishedCandidatesStillUsesHyde() {
        String question = "如何在大型 Java 并发系统中为不同用户设计锁粒度";
        List<String> embedded = new CopyOnWriteArrayList<>();
        when(lexical.searchActive(question, 40)).thenReturn(List.of());
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        when(vectors.searchActive("metro_article_chunks_read", new float[]{0F, 1F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        when(resolver.resolveCurrent(List.of())).thenReturn(List.of());
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER,
                command -> new UserAiRoutedResult(new AiChatResult("一段假设检索文档。", "stop",
                        8, 9, "test", "chat-test"), UserAiFundingSource.USER));

        guardedService(new RecordingExecutor(), command -> {
            String text = command.inputs().getFirst();
            embedded.add(text);
            return new EmbeddingResult(List.of(text.equals(question) ? new float[]{1F, 0F}
                    : new float[]{0F, 1F}), "test", "bge-m3");
        }).retrieve(new ArticleRetrievalQuery(7L, "long-low-recall", question,
                Instant.parse("2026-08-12T00:00:30Z"), route, () -> { }));

        assertThat(embedded).containsExactly(question, "一段假设检索文档。");
    }

    @Test
    void concurrentHydeCallsKeepFrozenRoutesAndQuestionsIsolated() throws Exception {
        RecordingExecutor capabilityExecutor = new RecordingExecutor();
        HydeHypotheticalDocumentService hyde = new HydeHypotheticalDocumentService(
                capabilityExecutor,
                (userId, command) -> { throw new AssertionError("mutable router used"); },
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(8), 600);
        List<String> firstPrompts = new CopyOnWriteArrayList<>();
        List<String> secondPrompts = new CopyOnWriteArrayList<>();
        PreparedUserAiChat firstRoute = recordingRoute(firstPrompts, "第一份文档");
        PreparedUserAiChat secondRoute = recordingRoute(secondPrompts, "第二份文档");

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> hyde.generate(1L, "first", "问题甲",
                    Instant.parse("2026-08-12T00:00:30Z"), firstRoute, () -> { }), pool);
            var second = CompletableFuture.supplyAsync(() -> hyde.generate(2L, "second", "问题乙",
                    Instant.parse("2026-08-12T00:00:30Z"), secondRoute, () -> { }), pool);

            assertThat(first.get()).isEqualTo("第一份文档");
            assertThat(second.get()).isEqualTo("第二份文档");
        }
        assertThat(firstPrompts).singleElement().asString().contains("问题甲").doesNotContain("问题乙");
        assertThat(secondPrompts).singleElement().asString().contains("问题乙").doesNotContain("问题甲");
    }

    private void assertInvalidHydeDoesNotStartSecondEmbedding(AiChatResult response) {
        List<String> embedded = new CopyOnWriteArrayList<>();
        when(lexical.searchActive("锁", 40)).thenReturn(List.of());
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        when(resolver.resolveCurrent(List.of())).thenReturn(List.of());
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER,
                command -> new UserAiRoutedResult(response, UserAiFundingSource.USER));

        ArticleRetrievalResult result = guardedService(new RecordingExecutor(), command -> {
            embedded.add(command.inputs().getFirst());
            return embedding.embed(command);
        }).retrieve(new ArticleRetrievalQuery(7L, "invalid-hyde", "锁",
                Instant.parse("2026-08-12T00:00:30Z"), route, () -> { }));

        assertThat(result.authorizedChunks()).isEmpty();
        assertThat(embedded).containsExactly("锁");
    }

    private PreparedUserAiChat recordingRoute(List<String> prompts, String result) {
        return new PreparedUserAiChat("frozen", UserAiFundingSource.USER, command -> {
            prompts.add(command.messages().getFirst().text());
            return new UserAiRoutedResult(new AiChatResult(result, "stop", 5, 5,
                    "test", "chat-test"), UserAiFundingSource.USER);
        });
    }

    @Test
    void shortConceptWithEnoughCandidatesUsesSemanticRoutingInsteadOfLength() {
        assertSemanticRouting("什么是缓存雪崩", "CONCEPTUAL", false);
    }

    @Test
    void longDescriptiveQuestionExpandsEvenWhenCandidatesAreEnough() {
        assertSemanticRouting("每天晚上八点数据库突然变得特别忙，过了一会儿又恢复正常，可能是什么原因", "DESCRIPTIVE", true);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"EXACT", "UNRESOLVED"})
    void exactAndUnresolvedIntentDoesNotInventAHypothesis(String intent) {
        assertSemanticRouting("查询目标", intent, false);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "null", "{\"type\":\"UNKNOWN\"}", "不是 JSON"})
    void invalidClassificationKeepsExistingEvidenceAndSkipsExtraEmbedding(String output) {
        String question = "什么是缓存雪崩";
        List<ResolvedArticleChunk> current = seedCandidates(question);
        List<String> embedded = new CopyOnWriteArrayList<>();
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER, command ->
                new UserAiRoutedResult(new AiChatResult(output, "stop", 8, 9, "test", "frozen"),
                        UserAiFundingSource.USER));
        ArticleRetrievalResult result = guardedService(new RecordingExecutor(), command -> {
            embedded.add(command.inputs().getFirst());
            return embedding.embed(command);
        }).retrieve(new ArticleRetrievalQuery(7L, "invalid-intent", question,
                Instant.parse("2026-08-12T00:00:30Z"), route, () -> { }));
        assertThat(result.authorizedChunks()).containsExactlyElementsOf(current);
        assertThat(embedded).containsExactly(question);
    }

    @Test
    void semanticPromptPreservesOriginalSceneBesideRewrittenSearchText() {
        String original = "每天晚上八点数据库突然繁忙，过一会儿又恢复，为什么";
        String rewritten = "缓存雪崩";
        seedCandidates(rewritten);
        List<String> prompts = new CopyOnWriteArrayList<>();
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER, command -> {
            if (command.responseMode() == cumt.zongzuo.community.ai.provider.AiResponseMode.JSON_OBJECT) {
                prompts.add(command.messages().stream().map(m -> m.text()).collect(java.util.stream.Collectors.joining("\n")));
            }
            return new UserAiRoutedResult(new AiChatResult("{\"type\":\"CONCEPTUAL\"}", "stop",
                    8, 9, "test", "frozen"), UserAiFundingSource.USER);
        });
        guardedService(new RecordingExecutor(), embedding).retrieve(new ArticleRetrievalQuery(7L,
                "original-question", rewritten, Instant.parse("2026-08-12T00:00:30Z"), route, () -> { }, original));
        assertThat(prompts).singleElement().asString().contains(original, rewritten);
    }

    @Test
    void cancellationDuringClassificationCannotBecomeSuccessfulFallback() {
        String question = "什么是缓存雪崩";
        seedCandidates(question);
        AtomicBoolean active = new AtomicBoolean(true);
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER, command -> {
            active.set(false);
            return new UserAiRoutedResult(new AiChatResult("{\"type\":\"DESCRIPTIVE\"}", "stop",
                    8, 9, "test", "frozen"), UserAiFundingSource.USER);
        });
        List<String> embedded = new CopyOnWriteArrayList<>();
        assertThatThrownBy(() -> guardedService(new RecordingExecutor(), command -> {
            embedded.add(command.inputs().getFirst());
            return embedding.embed(command);
        }).retrieve(new ArticleRetrievalQuery(7L, "cancel-intent", question,
                Instant.parse("2026-08-12T00:00:30Z"), route, () -> {
                    if (!active.get()) throw new java.util.concurrent.CancellationException("已取消");
                }))).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(embedded).containsExactly(question);
    }

    @Test
    void interruptedWhileClassificationIsQueuedCannotCallTheProvider() {
        String question = "什么是缓存雪崩";
        seedCandidates(question);
        AtomicBoolean called = new AtomicBoolean();
        RecordingExecutor executor = new RecordingExecutor();
        executor.beforeOperation = capability -> {
            if (capability == AiCapability.HYDE) Thread.currentThread().interrupt();
        };
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER, command -> {
            called.set(true);
            return new UserAiRoutedResult(new AiChatResult("{\"type\":\"CONCEPTUAL\"}", "stop",
                    8, 9, "test", "frozen"), UserAiFundingSource.USER);
        });
        try {
            assertThatThrownBy(() -> guardedService(executor, embedding).retrieve(new ArticleRetrievalQuery(
                    7L, "queued-interrupt", question, Instant.parse("2026-08-12T00:00:30Z"),
                    route, () -> { }))).isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(called).isFalse();
        } finally {
            // 本测试主动中断运行线程，清除标记，避免影响同线程的其他用例。
            Thread.interrupted();
        }
    }

    @Test
    void insufficientPublishedCandidatesTriggerHydeWithoutClassifyingConcept() {
        String question = "什么是缓存雪崩";
        List<ResolvedArticleChunk> current = seedCandidates(question);
        // 原始命中有三块，但只有一块仍然公开，数量触发必须使用过滤后的数量。
        when(resolver.resolveCurrent(List.of(61L, 62L, 63L))).thenReturn(current.subList(0, 1));
        List<cumt.zongzuo.community.ai.provider.AiResponseMode> modes = new CopyOnWriteArrayList<>();
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER, command -> {
            modes.add(command.responseMode());
            return harmlessRoute().generate(command);
        });
        guardedService(new RecordingExecutor(), embedding).retrieve(new ArticleRetrievalQuery(7L,
                "low-candidates", question, Instant.parse("2026-08-12T00:00:30Z"), route, () -> { }));
        assertThat(modes).containsExactly(cumt.zongzuo.community.ai.provider.AiResponseMode.TEXT);
    }

    /** 外部模型使用确定性返回值；检索、路由、融合及真实输出转换仍执行生产实现。 */
    private void assertSemanticRouting(String question, String intent, boolean expand) {
        List<ResolvedArticleChunk> current = seedCandidates(question);
        var classifications = new java.util.concurrent.atomic.AtomicInteger();
        List<String> embedded = new CopyOnWriteArrayList<>();
        PreparedUserAiChat route = new PreparedUserAiChat("frozen", UserAiFundingSource.USER, command -> {
            boolean classification = command.responseMode() == cumt.zongzuo.community.ai.provider.AiResponseMode.JSON_OBJECT;
            if (classification) classifications.incrementAndGet();
            return new UserAiRoutedResult(new AiChatResult(classification
                    ? "{\"type\":\"" + intent + "\"}" : "场景的假设性检索文档", "stop",
                    8, 9, "test", "frozen"), UserAiFundingSource.USER);
        });
        ArticleRetrievalResult result = guardedService(new RecordingExecutor(), command -> {
            embedded.add(command.inputs().getFirst());
            return embedding.embed(command);
        }).retrieve(new ArticleRetrievalQuery(7L, "semantic", question,
                Instant.parse("2026-08-12T00:00:30Z"), route, () -> { }));

        assertThat(result.authorizedChunks()).containsExactlyElementsOf(current);
        assertThat(embedded).containsExactlyElementsOf(expand
                ? List.of(question, "场景的假设性检索文档") : List.of(question));
        assertThat(classifications.get()).isEqualTo(1);
    }

    private List<ResolvedArticleChunk> seedCandidates(String question) {
        when(lexical.searchActive(question, 40)).thenReturn(List.of(
                new ArticleChunkSearchHit(61L, 601L, 6001L, 9F),
                new ArticleChunkSearchHit(62L, 602L, 6002L, 8F),
                new ArticleChunkSearchHit(63L, 603L, 6003L, 7F)));
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        List<ResolvedArticleChunk> current = List.of(
                resolved(61L, 601L, 6001L), resolved(62L, 602L, 6002L),
                resolved(63L, 603L, 6003L));
        when(resolver.resolveCurrent(List.of(61L, 62L, 63L))).thenReturn(current);
        return current;
    }

    @Test
    void semanticProviderFailurePreservesSufficientFirstRoundEvidence() {
        String question = "如何在大型 Java 并发系统中设计可以避免死锁的锁顺序";
        List<ArticleChunkSearchHit> hits = List.of(
                new ArticleChunkSearchHit(61L, 601L, 6001L, 9F),
                new ArticleChunkSearchHit(62L, 602L, 6002L, 8F),
                new ArticleChunkSearchHit(63L, 603L, 6003L, 7F));
        when(lexical.searchActive(question, 40)).thenReturn(hits);
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of());
        List<ResolvedArticleChunk> current = List.of(
                resolved(61L, 601L, 6001L), resolved(62L, 602L, 6002L),
                resolved(63L, 603L, 6003L));
        when(resolver.resolveCurrent(List.of(61L, 62L, 63L))).thenReturn(current);
        AtomicBoolean hydeCalled = new AtomicBoolean();
        RecordingExecutor executor = new RecordingExecutor();
        HydeHypotheticalDocumentService hyde = new HydeHypotheticalDocumentService(
                executor,
                (userId, command) -> {
                    hydeCalled.set(true);
                    throw new IllegalStateException("分类服务暂时不可用");
                },
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(8), 600);
        HybridArticleRetrievalService service = new HybridArticleRetrievalService(
                lexical, vectors, resolver, executor, embedding,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "metro_article_chunks_read", "bge-m3", 40, 8, Duration.ofSeconds(20),
                hyde, 3);

        ArticleRetrievalResult result = service.retrieve(new ArticleRetrievalQuery(
                11L, "req-hyde-skip", question, Instant.parse("2026-08-12T00:00:30Z")));

        assertThat(result.authorizedChunks()).containsExactlyElementsOf(current);
        assertThat(hydeCalled).isTrue();
        assertThat(executor.capabilities()).containsExactly(AiCapability.EMBEDDING, AiCapability.HYDE);
    }

    private static ResolvedArticleChunk resolved(long chunkId, long articleId, long revisionId) {
        return new ResolvedArticleChunk(chunkId, articleId, revisionId, 0, "Title", List.of(),
                "Body", "3".repeat(64), "4".repeat(64));
    }

    private HybridArticleRetrievalService service() {
        return new HybridArticleRetrievalService(lexical, vectors, resolver,
                new DirectExecutor(), embedding,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "metro_article_chunks_read", "bge-m3", 40, 8,
                Duration.ofSeconds(20));
    }

    private static final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                             CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.EMBEDDING);
            assertThat(context.background()).isFalse();
            try {
                return operation.get();
            } catch (Throwable error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public <A, T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                                AttemptObserver<A, T> observer, AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingExecutor implements AiCapabilityExecutor {
        private final List<AiCapability> capabilities = new CopyOnWriteArrayList<>();
        private java.util.function.Consumer<AiCapability> beforeOperation = capability -> { };

        @Override
        public <T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                             CheckedSupplier<T> operation) {
            capabilities.add(context.capability());
            try {
                beforeOperation.accept(context.capability());
                return operation.get();
            } catch (RuntimeException error) {
                throw error;
            } catch (Throwable error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public <A, T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                                AttemptObserver<A, T> observer, AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }

        private List<AiCapability> capabilities() {
            return List.copyOf(capabilities);
        }
    }
}
