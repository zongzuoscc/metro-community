package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.article.service.PublishedArticleReadService;
import cumt.zongzuo.community.entity.Article;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPageCapabilityServiceTest {

    @Test
    void summarizesServerOwnedPublishedArticleAndReportsWhoPays() {
        PublishedArticleReadService articles = mock(PublishedArticleReadService.class);
        UserAiChatRouter router = mock(UserAiChatRouter.class);
        Article article = new Article();
        article.setId(42L);
        article.setStatus(1);
        article.setIsDeleted(0);
        article.setTitle("不应从 DOM 读取的文章");
        article.setSummary("摘要");
        article.setContent("服务端发布正文");
        when(articles.findById(42L)).thenReturn(article);
        when(router.generate(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UserAiRoutedResult(
                        new AiChatResult("三句话总结", "stop", 20, 8,
                                "openai", "gpt-4.1-mini"), UserAiFundingSource.USER));
        AgentPageCapabilityService service = new AgentPageCapabilityService(
                articles, router, directExecutor(new AtomicReference<>()), fixedClock(),
                Duration.ofSeconds(30), Duration.ofSeconds(30), 100_000, 20_000);

        AgentCapabilityResponse result = service.summarizeArticle(7L, 42L);

        assertThat(result.content()).isEqualTo("三句话总结");
        assertThat(result.fundingSource()).isEqualTo(UserAiFundingSource.USER);
        verify(router).generate(org.mockito.ArgumentMatchers.eq(7L), argThat(command ->
                command.capability() == AiCapability.ARTICLE_SUMMARY
                        && command.messages().stream().anyMatch(message ->
                        message.text().contains("服务端发布正文"))));
    }

    @Test
    void articleCoreViewStillUsesTheServerOwnedPublishedArticle() {
        PublishedArticleReadService articles = mock(PublishedArticleReadService.class);
        UserAiChatRouter router = mock(UserAiChatRouter.class);
        Article article = new Article();
        article.setId(43L);
        article.setStatus(1);
        article.setIsDeleted(0);
        article.setTitle("服务端文章");
        article.setContent("服务端发布正文");
        when(articles.findById(43L)).thenReturn(article);
        when(router.generate(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UserAiRoutedResult(new AiChatResult("核心观点", "stop", 8, 4,
                        "openai", "gpt-4.1-mini"), UserAiFundingSource.USER));
        AgentPageCapabilityService service = new AgentPageCapabilityService(
                articles, router, directExecutor(new AtomicReference<>()), fixedClock(),
                Duration.ofSeconds(30), Duration.ofSeconds(30), 100_000, 20_000);

        AgentCapabilityResponse result = service.analyzeArticle(7L, 43L, "CORE");

        assertThat(result.content()).isEqualTo("核心观点");
        verify(router).generate(org.mockito.ArgumentMatchers.eq(7L), argThat(command ->
                command.messages().stream().anyMatch(message -> message.text().contains("提炼核心观点"))
                        && command.messages().stream().anyMatch(message ->
                        message.text().contains("服务端发布正文"))));
    }

    @Test
    void writingReturnsAProposalWithoutMutatingAnyArticle() {
        UserAiChatRouter router = mock(UserAiChatRouter.class);
        when(router.generate(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UserAiRoutedResult(
                        new AiChatResult("更清晰的表达", "stop", 12, 6,
                                "deepseek", "deepseek-chat"), UserAiFundingSource.PLATFORM));
        AgentPageCapabilityService service = new AgentPageCapabilityService(
                mock(PublishedArticleReadService.class), router,
                directExecutor(new AtomicReference<>()), fixedClock(),
                Duration.ofSeconds(30), Duration.ofSeconds(30), 100_000, 20_000);

        WritingSuggestionResponse result = service.suggestWriting(7L,
                new WritingSuggestionRequest("POLISH", "标题", "全文", "原始选区", 4, 8, 12));

        assertThat(result.suggestedText()).isEqualTo("更清晰的表达");
        assertThat(result.documentVersion()).isEqualTo(12);
        assertThat(result.selectionFrom()).isEqualTo(4);
        assertThat(result.selectionTo()).isEqualTo(8);
        verify(router).generate(org.mockito.ArgumentMatchers.eq(7L), argThat((AiChatCommand command) ->
                command.capability() == AiCapability.WRITING));
    }

    @Test
    void pageCapabilityAlwaysPassesThroughTheExistingQuotaAndDeadlineExecutor() {
        UserAiChatRouter router = mock(UserAiChatRouter.class);
        when(router.generate(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UserAiRoutedResult(new AiChatResult("建议", "stop", 1, 1,
                        "deepseek", "deepseek-chat"), UserAiFundingSource.PLATFORM));
        AtomicReference<AiInvocationContext> invocation = new AtomicReference<>();
        AgentPageCapabilityService service = new AgentPageCapabilityService(
                mock(PublishedArticleReadService.class), router, directExecutor(invocation),
                fixedClock(), Duration.ofSeconds(20), Duration.ofSeconds(30), 100_000, 20_000);

        service.suggestWriting(7L, new WritingSuggestionRequest(
                "POLISH", "标题", "全文", "选区", 1, 3, 4));

        assertThat(invocation.get().capability()).isEqualTo(AiCapability.WRITING);
        assertThat(invocation.get().userId()).isEqualTo(7L);
        assertThat(invocation.get().deadline())
                .isEqualTo(Instant.parse("2026-08-12T00:00:30Z"));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
    }

    private static AiCapabilityExecutor directExecutor(AtomicReference<AiInvocationContext> invocation) {
        return new AiCapabilityExecutor() {
            @Override
            public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
                invocation.set(context);
                try {
                    return operation.get();
                }
                catch (Throwable error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public <A, T> T execute(AiInvocationContext context, AttemptObserver<A, T> observer,
                                    AttemptOperation<A, T> operation) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
