package cumt.zongzuo.community.article.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleChunkerTest {

    @Test
    void deterministicChunkIdUsesTheFrozenLow63BitAlgorithm() {
        assertThat(ArticleChunkId.from(7L, 1L, "commonmark-bgem3-v1", 0, "a".repeat(64)))
                .isEqualTo(1_026_365_987_335_167_608L);
    }

    @Test
    void commonMarkBlocksPreserveHeadingPathsCodeAndUnicodeOffsets() {
        ArticleChunker chunker = new ArticleChunker(512);
        String markdown = """
                # 总览

                第一段 😀。

                ```java
                int answer = 42;
                ```

                ## 细节

                - 项目 A
                - 项目 B
                """;

        List<ArticleChunkDraft> chunks = chunker.chunk(7L, 1L, "标题", markdown);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.startCodepoint()).isLessThanOrEqualTo(chunk.endCodepoint());
            assertThat(chunk.chunkHash()).matches("[0-9a-f]{64}");
            assertThat(chunk.embeddingInputHash()).matches("[0-9a-f]{64}");
            assertThat(chunk.estimatedTokens()).isBetween(1, 512);
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.headingPath()).containsExactly("总览");
            assertThat(chunk.bodyText()).contains("第一段 😀。", "int answer = 42;");
        });
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.headingPath()).containsExactly("总览", "细节"));
    }

    @Test
    void identicalInputIsByteStableAndAParserGenerationChangesIdentity() {
        ArticleChunker chunker = new ArticleChunker(512);
        String markdown = "# H\n\n相同内容";

        List<ArticleChunkDraft> first = chunker.chunk(9L, 1L, "T", markdown);
        List<ArticleChunkDraft> replay = chunker.chunk(9L, 1L, "T", markdown);
        List<ArticleChunkDraft> nextGeneration = chunker.chunk(9L, 2L, "T", markdown);

        assertThat(replay).isEqualTo(first);
        assertThat(nextGeneration).extracting(ArticleChunkDraft::id)
                .doesNotContainAnyElementsOf(first.stream().map(ArticleChunkDraft::id).toList());
    }

    @Test
    void adjacentChunksCarryDeterministicSemanticOverlapWithinTheTokenCap() {
        ArticleChunker chunker = new ArticleChunker(512);
        String paragraph = "知识检索需要保持事实边界、版本身份和稳定排序，避免旧内容覆盖新内容。";
        StringBuilder markdown = new StringBuilder("# 检索\n\n");
        for (int index = 0; index < 24; index++) {
            markdown.append(paragraph).append(" 段落编号 ").append(index).append("。\n\n");
        }

        List<ArticleChunkDraft> chunks = chunker.chunk(11L, 1L, "知识库", markdown.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(512));
        for (int index = 1; index < chunks.size(); index++) {
            Set<String> previousParagraphs = paragraphs(chunks.get(index - 1).bodyText());
            Set<String> currentParagraphs = paragraphs(chunks.get(index).bodyText());
            assertThat(previousParagraphs).containsAnyElementsOf(currentParagraphs);
            assertThat(chunks.get(index).startCodepoint())
                    .isLessThan(chunks.get(index - 1).endCodepoint());
        }
    }

    @Test
    void oneOversizedParagraphSplitsOnCodepointBoundariesInsteadOfRejectingTheArticle() {
        ArticleChunker chunker = new ArticleChunker(64);
        String markdown = "# 超长\n\n" + "中文事实😀需要可恢复地切分。".repeat(160);

        List<ArticleChunkDraft> chunks = chunker.chunk(12L, 1L, "长文", markdown);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.estimatedTokens()).isBetween(1, 64);
            assertThat(chunk.bodyText()).doesNotContain("�");
            assertThat(chunk.bodyText().chars()
                    .filter(value -> Character.isHighSurrogate((char) value)).count())
                    .isEqualTo(chunk.bodyText().chars()
                            .filter(value -> Character.isLowSurrogate((char) value)).count());
        });
    }

    private static Set<String> paragraphs(String body) {
        return List.of(body.split("\\n\\n")).stream().collect(Collectors.toSet());
    }
}
