package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.runtime.*;
import cumt.zongzuo.community.ai.userprovider.*;
import cumt.zongzuo.community.article.projection.chunk.*;
import cumt.zongzuo.community.article.projection.vector.*;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 共享检索服务的并发契约测试：每个请求使用独立查询、冻结模型与文章 ID。
 * 检索、HyDE、融合和后处理使用真实业务组件；数据库/模型以确定性替身隔离。
 * 输出只衡量本地组件处理，不是 ES、Milvus、MySQL 或真实模型的生产容量。
 */
class HybridRetrievalLoadTest {
    private static final int REQUESTS = 1000;
    private static final int SLOTS = 128;
    private static final Pattern ID = Pattern.compile("(?:q|hypothesis):(\\d+)");

    @Test
    void concurrentThreeWayAndTwoWayRequestsKeepRanksRoutesAndEvidenceIsolated() throws Exception {
        runBatch(true);
        runBatch(false);
    }

    private void runBatch(boolean useHyde) throws Exception {
        AtomicInteger lexicalCalls = new AtomicInteger();
        AtomicInteger vectorCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger embeddingCalls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        var executor = new DirectExecutor();
        ArticleChunkSearchRepository lexical = (query, topK) -> {
            lexicalCalls.incrementAndGet();
            pause();
            long base = id(query) * 100L;
            return List.of(lexical(base, 1), lexical(base, 2), lexical(base, 3));
        };
        EmbeddingGateway embedding = command -> {
            embeddingCalls.incrementAndGet();
            String input = command.inputs().getFirst();
            pause();
            return new EmbeddingResult(List.of(new float[]{id(input),
                    input.startsWith("hypothesis:") ? 1F : 0F}), "fixture", "fixture-embedding");
        };
        var hyde = new HydeHypotheticalDocumentService(executor,
                (user, command) -> { throw new AssertionError("冻结路由不应重新读取用户配置"); },
                Clock.systemUTC(), Duration.ofSeconds(10), 600);
        // 一个共享实例处理全部请求，才能捕获新增单例字段导致的串线。
        var service = new HybridArticleRetrievalService(lexical, new Vectors(vectorCalls),
                new Resolver(), executor, embedding, Clock.systemUTC(), "fixture-alias",
                "fixture-embedding", 40, 8, Duration.ofSeconds(10), hyde, 18, 3);
        var start = new CountDownLatch(1);
        var slots = new Semaphore(SLOTS);
        List<Future<Long>> futures = new ArrayList<>();
        long batchStart = System.nanoTime();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= REQUESTS; i++) {
                final int user = i;
                futures.add(threads.submit(() -> {
                    start.await();
                    slots.acquire();
                    long begin = System.nanoTime();
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        String question = "q:" + user + (useHyde ? "" : " 请详细解释大型系统如何保持数据一致性与检索可见性");
                        var route = new PreparedUserAiChat("fixture-model-" + user, UserAiFundingSource.USER,
                                command -> {
                                    modelCalls.incrementAndGet();
                                    assertThat(command.capability()).isEqualTo(AiCapability.HYDE);
                                    String prompt = command.messages().stream().map(AiPromptMessage::text)
                                            .reduce("", (a, b) -> a + "\n" + b);
                                    var matcher = ID.matcher(prompt);
                                    List<Integer> mentioned = new ArrayList<>();
                                    while (matcher.find()) mentioned.add(Integer.parseInt(matcher.group(1)));
                                    assertThat(mentioned).containsExactly(user);
                                    pause();
                                    return new UserAiRoutedResult(new AiChatResult("hypothesis:" + user,
                                            "stop", 8, 8, "fixture", "fixture-model-" + user), UserAiFundingSource.USER);
                                });
                        var result = service.retrieve(new ArticleRetrievalQuery(user, "fixture-" + user,
                                question, Instant.now().plusSeconds(30), route, () -> { }));
                        long base = user * 100L;
                        assertThat(result.lexicalAvailable()).isTrue();
                        assertThat(result.denseAvailable()).isTrue();
                        assertThat(result.lexicalCount()).isEqualTo(3);
                        assertThat(result.denseCount()).isEqualTo(4);
                        assertThat(result.rankedCandidates()).extracting(RankedArticleChunk::chunkId)
                                .containsExactlyElementsOf(useHyde
                                        ? List.of(base + 2, base + 4, base + 3, base + 1)
                                        : List.of(base + 2, base + 3, base + 1, base + 4));
                        var winner = result.rankedCandidates().getFirst();
                        assertThat(winner.lexicalRank()).isEqualTo(2);
                        assertThat(winner.denseRank()).isEqualTo(1);
                        if (useHyde) {
                            assertThat(winner.hydeRank()).isEqualTo(2);
                            assertThat(winner.rrfScore()).isCloseTo(0.048651507139079855, within(1e-12));
                        } else {
                            assertThat(winner.hydeRank()).isNull();
                            assertThat(winner.rrfScore()).isCloseTo(0.03252247488101534, within(1e-12));
                        }
                        // 索引陈旧副本和假设文档都不能成为返回证据。
                        assertThat(result.authorizedChunks()).allSatisfy(chunk -> {
                            assertThat(chunk.bodyText()).isEqualTo("published:" + user);
                            assertThat(chunk.chunkId()).isBetween(base + 1, base + 5);
                            assertThat(chunk.title()).isEqualTo("fixture");
                            assertThat(chunk.headingPath()).containsExactly("section");
                            assertThat(chunk.revisionId()).isEqualTo(base + 50);
                            assertThat(chunk.revisionContentHash()).isEqualTo("a".repeat(64));
                            assertThat(chunk.chunkHash()).isEqualTo("b".repeat(64));
                        });
                        return System.nanoTime() - begin;
                    } finally {
                        active.decrementAndGet();
                        slots.release();
                    }
                }));
            }
            start.countDown();
            List<Long> latency = new ArrayList<>();
            for (var future : futures) latency.add(future.get(45, TimeUnit.SECONDS));
            latency.sort(Long::compareTo);
            assertThat(active.get()).isZero();
            assertThat(peak.get()).isBetween(2, SLOTS);
            assertThat(lexicalCalls.get()).isEqualTo(REQUESTS);
            assertThat(vectorCalls.get()).isEqualTo(REQUESTS * (useHyde ? 2 : 1));
            assertThat(embeddingCalls.get()).isEqualTo(REQUESTS * (useHyde ? 2 : 1));
            assertThat(modelCalls.get()).isEqualTo(useHyde ? REQUESTS : 0);
            System.out.printf(Locale.ROOT,
                    "RAG_COMPONENT_LOAD {\"mode\":\"%s\",\"requests\":%d,\"success\":%d,\"slots\":%d,\"peak\":%d,\"batchMs\":%.3f,\"executionP95Ms\":%.3f,\"lexical\":%d,\"vectors\":%d,\"embeddings\":%d,\"hyde\":%d}%n",
                    useHyde ? "three-way" : "two-way", REQUESTS, latency.size(), SLOTS, peak.get(),
                    (System.nanoTime() - batchStart) / 1e6, latency.get(949) / 1e6,
                    lexicalCalls.get(), vectorCalls.get(), embeddingCalls.get(), modelCalls.get());
        }
    }

    private static int id(String text) {
        var matcher = ID.matcher(text);
        if (!matcher.find()) throw new AssertionError("检索输入丢失请求标识");
        return Integer.parseInt(matcher.group(1));
    }

    private static void pause() {
        try { Thread.sleep(2); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("测试依赖已取消");
        }
    }

    private static long article(long base, int offset) {
        return offset == 1 || offset == 3 || offset == 5 ? base + 10 : base + offset + 10;
    }

    private static ArticleChunkSearchHit lexical(long base, int offset) {
        return new ArticleChunkSearchHit(base + offset, article(base, offset), base + 50, 1F);
    }

    private static ArticleVectorHit vector(long base, int offset) {
        return new ArticleVectorHit(base + offset, article(base, offset), base + 50, .9F);
    }

    private static final class Vectors implements ArticleVectorRepository {
        private final AtomicInteger calls;
        Vectors(AtomicInteger calls) { this.calls = calls; }
        public List<ArticleVectorHit> searchActive(String alias, float[] embedding, int topK,
                                                   String model, long generation) {
            calls.incrementAndGet();
            assertThat(alias).isEqualTo("fixture-alias");
            assertThat(model).isEqualTo("fixture-embedding");
            assertThat(generation).isEqualTo(3L);
            pause();
            long base = (long) embedding[0] * 100;
            return IntStream.of(embedding[1] == 1F ? new int[]{4, 2, 5} : new int[]{2, 4, 9, 3})
                    .mapToObj(offset -> vector(base, offset)).toList();
        }
        public List<Long> listChunkIdsByArticle(String collection, long article) { throw new AssertionError("只读检索"); }
        public long upsert(String collection, List<ArticleVectorDocument> documents) { throw new AssertionError("只读检索"); }
        public long deleteByChunkIds(String collection, List<Long> ids) { throw new AssertionError("只读检索"); }
        public void assertDeletedStrong(String collection, List<Long> ids) { throw new AssertionError("只读检索"); }
    }

    private static final class Resolver extends PublishedArticleChunkResolver {
        Resolver() { super(null, null); }
        public long activeParserGeneration() { return 3; }
        public List<ResolvedArticleChunk> resolveCurrent(List<Long> ids) {
            pause();
            return ids.stream().filter(id -> id % 100 != 9).map(id -> {
                long base = id / 100 * 100;
                return new ResolvedArticleChunk(id, article(base, (int) (id % 100)), base + 50,
                        0, "fixture", List.of("section"), "published:" + id / 100,
                        "a".repeat(64), "b".repeat(64));
            }).toList();
        }
    }

    private static final class DirectExecutor implements AiCapabilityExecutor {
        public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
            try { return operation.get(); }
            catch (RuntimeException | Error error) { throw error; }
            catch (Throwable error) { throw new IllegalStateException(error); }
        }
        public <A, T> T execute(AiInvocationContext context, AttemptObserver<A, T> observer,
                                AttemptOperation<A, T> operation) { throw new AssertionError("未使用的执行接口"); }
    }
}
