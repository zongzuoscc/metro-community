package cumt.zongzuo.community.ai.agent;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class StreamingAnswerTextTest {
    @Test
    void emitsUnclosedAnswerAndDecodesEscapesWithoutLeakingMetadata() {
        var parts = new ArrayList<String>();
        var decoder = new StreamingAnswerText(parts::add);
        decoder.accept("{\"citations\":[{\"quote\":\"不要输出\"}],\"answer\":\"第一");
        assertThat(String.join("", parts)).isEqualTo("第一");
        decoder.accept("行\\n\\u4e");
        decoder.accept("8c\\uD83D");
        decoder.accept("\\uDE00\\\"好\\\"\"}");
        decoder.finish();
        assertThat(String.join("", parts)).isEqualTo("第一行\n二😀\"好\"");
    }

    @Test
    void rejectsTruncationDuplicateAnswerAndOversizedContent() {
        var truncated = new StreamingAnswerText(ignored -> {});
        truncated.accept("{\"answer\":\"partial");
        assertThatThrownBy(truncated::finish).isInstanceOf(IllegalArgumentException.class);
        var duplicate = new StreamingAnswerText(ignored -> {});
        assertThatThrownBy(() -> duplicate.accept("{\"answer\":\"a\",\"answer\":\"b\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        var large = new StreamingAnswerText(ignored -> {});
        assertThatThrownBy(() -> large.accept("{\"answer\":\"" + "x".repeat(12001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
