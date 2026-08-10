package cumt.zongzuo.community.article.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StageBElasticsearchPitCursorTest {

    @Test
    void alwaysUsesAndClosesTheLatestNonBlankPitIdReturnedByElasticsearch() {
        StageBElasticsearchPitCursor cursor = new StageBElasticsearchPitCursor("opened");

        assertThat(cursor.current()).isEqualTo("opened");
        cursor.advance("page-one-rotated");
        assertThat(cursor.current()).isEqualTo("page-one-rotated");
        cursor.advance(null);
        cursor.advance("  ");
        assertThat(cursor.current()).isEqualTo("page-one-rotated");
        cursor.advance("page-two-rotated");

        assertThat(cursor.current()).isEqualTo("page-two-rotated");
    }
}
