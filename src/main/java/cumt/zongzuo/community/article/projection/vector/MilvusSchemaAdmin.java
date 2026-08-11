package cumt.zongzuo.community.article.projection.vector;

import io.milvus.v2.service.collection.request.CreateCollectionReq;

public interface MilvusSchemaAdmin {
    void ensureExact(CreateCollectionReq schema, String readAlias);
}
