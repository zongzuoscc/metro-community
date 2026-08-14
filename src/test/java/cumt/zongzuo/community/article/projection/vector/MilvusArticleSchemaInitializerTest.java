package cumt.zongzuo.community.article.projection.vector;

import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusArticleSchemaInitializerTest {

    @Test
    void localInitializerCreatesOnlyTheExactArticleCollectionAndReadAlias() {
        RecordingAdmin admin = new RecordingAdmin();

        new MilvusArticleSchemaInitializer(admin).initialize();

        assertThat(MilvusCollectionSchemas.fingerprint(admin.schema))
                .isEqualTo(MilvusCollectionSchemas.fingerprint(MilvusCollectionSchemas.article()));
        assertThat(admin.alias).isEqualTo(MilvusCollectionSchemas.ARTICLE_ALIAS);
        assertThat(admin.calls).isEqualTo(1);
    }

    private static final class RecordingAdmin implements MilvusSchemaAdmin {
        private CreateCollectionReq schema;
        private String alias;
        private int calls;

        @Override
        public void ensureExact(CreateCollectionReq schema, String readAlias) {
            this.schema = schema;
            this.alias = readAlias;
            this.calls++;
        }
    }
}
