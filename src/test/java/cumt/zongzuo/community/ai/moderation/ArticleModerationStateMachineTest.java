package cumt.zongzuo.community.ai.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.moderation.revision.ModerationAggregate;
import cumt.zongzuo.community.ai.moderation.revision.ModerationChunk;
import cumt.zongzuo.community.ai.moderation.revision.ModerationChunker;
import cumt.zongzuo.community.ai.moderation.revision.ModerationDecision;
import cumt.zongzuo.community.ai.moderation.revision.ModerationModelOutput;
import cumt.zongzuo.community.ai.moderation.revision.ModerationOutputParser;
import cumt.zongzuo.community.ai.moderation.revision.ModerationPromptFactory;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ArticleModerationStateMachineTest {

    private static final String MODEL = "shadow-moderator";
    private static final String PROMPT_VERSION = "moderation-v1";
    private final ModerationOutputParser parser = new ModerationOutputParser(new ObjectMapper());

    @Test
    void acceptsOnlyCompleteRevisionBoundJsonObjectOutput() {
        AiChatResult result = result("""
                {
                  "decision":"REVIEW",
                  "categories":["HARASSMENT"],
                  "severity":2,
                  "confidence":0.92,
                  "evidenceOffsets":[{"start":2,"end":5}],
                  "reason":"requires a human decision",
                  "model":"shadow-moderator",
                  "promptVersion":"moderation-v1"
                }
                """, "stop");

        ModerationModelOutput output = parser.parse(result, MODEL, PROMPT_VERSION, 12);

        assertThat(output.decision()).isEqualTo(ModerationDecision.REVIEW);
        assertThat(output.confidence()).isEqualByComparingTo(new BigDecimal("0.92"));
        assertThat(output.evidenceOffsets()).singleElement().satisfies(evidence -> {
            assertThat(evidence.start()).isEqualTo(2);
            assertThat(evidence.end()).isEqualTo(5);
        });
    }

    @Test
    void rejectsUnknownFieldsCategoriesBadEvidenceIdentityAndTruncation() {
        assertRejected(jsonWith("\"unexpected\":true,"), "stop");
        assertRejected(jsonWith("\"categories\":[\"NOT_A_POLICY\"],"), "stop");
        assertRejected(jsonWith("\"evidenceOffsets\":[{\"start\":4,\"end\":13}],"), "stop");
        assertRejected(jsonWith("\"model\":\"different-model\","), "stop");
        assertRejected(jsonWith("\"promptVersion\":\"old-prompt\","), "stop");
        assertRejected(jsonWith(""), "length");
    }

    @Test
    void rejectsMissingNullCoercedDuplicateOrOversizedJsonMembers() {
        assertRejected(validJson().replace("\"severity\":0,", ""), "stop");
        assertRejected(validJson().replace("\"severity\":0", "\"severity\":null"), "stop");
        assertRejected(validJson().replace("\"severity\":0", "\"severity\":\"0\""), "stop");
        assertRejected(validJson().replace("\"severity\":0", "\"severity\":0.5"), "stop");
        assertRejected(validJson().replace("{\"start\":0,\"end\":1}", "{\"end\":1}"), "stop");
        assertRejected(validJson().replace("\"decision\":\"PASS\",",
                "\"decision\":\"PASS\",\"decision\":\"REJECT\","), "stop");
        assertRejected(validJson().replace("\"categories\":[\"SPAM\"]",
                "\"categories\":[\"SPAM\",\"SPAM\"]"), "stop");
        assertRejected(validJson().replace("safe", "x".repeat(20_000)), "stop");
        assertRejected(validJson() + "{}", "stop");
        assertRejected(validJson().replace("\"confidence\":0.92", "\"confidence\":null"), "stop");
        assertRejected(validJson().replace("\"reason\":\"safe\"", "\"reason\":\" \""), "stop");
    }

    @Test
    void highestRiskWinsAndContradictionNeverBecomesPublishAuthority() {
        ModerationModelOutput pass = parser.parse(result(validJson(), "stop"),
                MODEL, PROMPT_VERSION, 12);
        ModerationModelOutput reject = parser.parse(result(validJson()
                        .replace("\"decision\":\"PASS\"", "\"decision\":\"REJECT\"")
                        .replace("\"severity\":0", "\"severity\":4"), "stop"),
                MODEL, PROMPT_VERSION, 12);

        ModerationAggregate aggregate = ModerationAggregate.from(List.of(pass, reject),
                new BigDecimal("0.80"));

        assertThat(aggregate.decision()).isEqualTo(ModerationDecision.REJECT);
        assertThat(aggregate.uncertain()).isTrue();
        assertThat(aggregate.requiresHumanReview()).isTrue();
    }

    @Test
    void chunksByTokenBudgetWithHeadingContextAndSourceOverlap() {
        ModerationChunker chunker = chunker(800, 50, 32, 50_000, 80);
        String body = """
                # Root
                %s
                ## Child
                %s
                """.formatted("alpha beta gamma delta ".repeat(300),
                "one two three four five six ".repeat(300));

        List<ModerationChunk> chunks = chunker.chunk("Revision title", body);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(800);
            assertThat(chunk.headingPath()).startsWith("Revision title");
            assertThat(chunk.content()).isNotBlank();
        });
        for (int index = 1; index < chunks.size(); index++) {
            assertThat(chunks.get(index).sourceStart())
                    .as("adjacent chunks preserve source overlap")
                    .isLessThan(chunks.get(index - 1).sourceEnd());
        }
    }

    @Test
    void rejectsEmptyContentAndHardChunkOrWholeTaskTokenCaps() {
        assertThatThrownBy(() -> chunker(800, 50, 3, 2_000, 80).chunk("", "  "))
                .isInstanceOf(IllegalArgumentException.class);

        String longBody = "word ".repeat(2_000);
        assertThatThrownBy(() -> chunker(800, 50, 1, 10_000, 80).chunk("title", longBody))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk cap");
        assertThatThrownBy(() -> chunker(800, 50, 20, 100, 80).chunk("title", longBody))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token cap");
    }

    @Test
    void wholeTaskCapIncludesExactPromptOverheadAndReservedOutputBeforeAnyCall() {
        assertThatThrownBy(() -> chunker(800, 50, 4, 100, 80)
                .chunk("title", "tiny body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token cap");
    }

    @Test
    void emojiBoundariesAlwaysAdvanceAndNeverSplitSurrogatePairs() {
        TokenCountEstimator estimator = estimator(String::length);
        ModerationPromptFactory factory = new ModerationPromptFactory(new ObjectMapper());
        String fittingContent = "a😀";
        int raw = factory.create(new ModerationChunk(0, "title", fittingContent,
                        0, fittingContent.length(), 0), MODEL).messages().stream()
                .mapToInt(message -> estimator.estimate(
                        "ROLE=" + message.role().name() + "\n" + message.text()))
                .sum();
        int exactCap = Math.toIntExact((Math.multiplyExact((long) raw, 5) + 3) / 4 + 16);
        ModerationChunker chunker = new ModerationChunker(estimator, factory, MODEL,
                exactCap, 0, 4, 100_000, 1, 1);

        List<ModerationChunk> chunks = assertTimeoutPreemptively(Duration.ofMillis(250),
                () -> chunker.chunk("title", fittingContent));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(fittingContent);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(Character.isLowSurrogate(chunk.content().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(
                    chunk.content().charAt(chunk.content().length() - 1))).isFalse();
        });
    }

    @Test
    void wholeTaskBudgetReservesEveryPossibleBackgroundProviderAttempt() {
        TokenCountEstimator estimator = estimator(ignored -> 1);
        ModerationPromptFactory factory = new ModerationPromptFactory(new ObjectMapper());
        ModerationChunker singleAttempt = new ModerationChunker(estimator, factory, MODEL,
                100, 0, 1, 100, 80, 1);
        ModerationChunker threeAttempts = new ModerationChunker(estimator, factory, MODEL,
                100, 0, 1, 100, 80, 3);

        assertThat(singleAttempt.chunk("title", "body")).hasSize(1);
        assertThatThrownBy(() -> threeAttempts.chunk("title", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token cap");
    }

    @Test
    void wholeTaskCostCapIsIndependentFromTheTokenCapAndIncludesRetries() {
        TokenCountEstimator estimator = estimator(ignored -> 1);
        ModerationPromptFactory factory = new ModerationPromptFactory(new ObjectMapper());
        ModerationChunker oneAttempt = new ModerationChunker(estimator, factory, MODEL,
                100, 0, 1, 1_000, 80, 1,
                100, 1_000_000, 1_000_000);
        ModerationChunker threeAttempts = new ModerationChunker(estimator, factory, MODEL,
                100, 0, 1, 1_000, 80, 3,
                100, 1_000_000, 1_000_000);

        assertThat(oneAttempt.chunk("title", "body")).hasSize(1);
        assertThatThrownBy(() -> threeAttempts.chunk("title", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cost cap");
    }

    private static ModerationChunker chunker(int maxChunkTokens, int overlapTokens,
                                             int maxChunks, int maxTotalTokens,
                                             int reservedOutputTokens) {
        return new ModerationChunker(new JTokkitTokenCountEstimator(),
                new ModerationPromptFactory(new ObjectMapper()), MODEL,
                maxChunkTokens, overlapTokens, maxChunks, maxTotalTokens,
                reservedOutputTokens, 3);
    }

    private static TokenCountEstimator estimator(java.util.function.ToIntFunction<String> estimate) {
        return new JTokkitTokenCountEstimator() {
            @Override
            public int estimate(String text) {
                return estimate.applyAsInt(text);
            }
        };
    }

    private void assertRejected(String json, String finishReason) {
        assertThatThrownBy(() -> parser.parse(result(json, finishReason), MODEL, PROMPT_VERSION, 12))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiChatResult result(String text, String finishReason) {
        return new AiChatResult(text, finishReason, 40, 20, "deepseek", MODEL);
    }

    private static String jsonWith(String replacement) {
        String json = """
                {
                  "decision":"PASS",
                  "categories":[],
                  "severity":0,
                  "confidence":0.92,
                  "evidenceOffsets":[],
                  "reason":"no deterministic policy hit",
                  "model":"shadow-moderator",
                  "promptVersion":"moderation-v1"
                }
                """;
        if (replacement.isEmpty()) {
            return json;
        }
        String field = replacement.substring(0, replacement.indexOf(':') + 1);
        int fieldIndex = json.indexOf(field);
        if (fieldIndex >= 0) {
            int valueStart = fieldIndex + field.length();
            int valueEnd = json.indexOf(',', valueStart);
            return json.substring(0, fieldIndex) + replacement + json.substring(valueEnd + 1);
        }
        return json.replaceFirst("\\{", "{" + replacement);
    }

    private static String validJson() {
        return """
                {"decision":"PASS","categories":["SPAM"],"severity":0,"confidence":0.92,
                 "evidenceOffsets":[{"start":0,"end":1}],"reason":"safe",
                 "model":"shadow-moderator","promptVersion":"moderation-v1"}
                """;
    }
}
