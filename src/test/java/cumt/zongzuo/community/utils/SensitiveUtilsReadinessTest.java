package cumt.zongzuo.community.utils;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveUtilsReadinessTest {

    @Test
    void emptyDictionaryIsNotReadyAndCannotBeInterpretedAsSafe() {
        SensitiveUtils rules = new SensitiveUtils(new ByteArrayResource(new byte[0]));

        rules.init();

        assertThat(rules.isReady()).isFalse();
        assertThatThrownBy(() -> rules.findFirst("ordinary text"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readyScanReturnsOnlyOffsetsAndNeverTheMatchedDictionaryWord() {
        SensitiveUtils rules = new SensitiveUtils(new ByteArrayResource(
                "private-policy-token\n".getBytes(StandardCharsets.UTF_8)));

        rules.init();

        assertThat(rules.isReady()).isTrue();
        assertThat(rules.findFirst("xxprivate-policy-tokenyy"))
                .contains(new SensitiveUtils.Match(2, 22));
        assertThat(rules.findFirst("ordinary text")).isEmpty();
    }
}
