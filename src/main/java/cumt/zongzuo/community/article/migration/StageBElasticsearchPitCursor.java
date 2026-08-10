package cumt.zongzuo.community.article.migration;

import org.springframework.util.StringUtils;

final class StageBElasticsearchPitCursor {

    private String current;

    StageBElasticsearchPitCursor(String openedPitId) {
        if (!StringUtils.hasText(openedPitId)) {
            throw new IllegalArgumentException("opened Elasticsearch PIT id must not be blank");
        }
        this.current = openedPitId;
    }

    String current() {
        return current;
    }

    void advance(String responsePitId) {
        if (StringUtils.hasText(responsePitId)) {
            current = responsePitId;
        }
    }
}
