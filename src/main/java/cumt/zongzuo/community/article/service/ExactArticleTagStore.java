package cumt.zongzuo.community.article.service;

import cumt.zongzuo.community.mapper.TagMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/** Atomic exact-collation get-or-create for compatibility tag rows. */
@Component
public class ExactArticleTagStore {

    private final TagMapper tagMapper;

    public ExactArticleTagStore(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    public long getOrCreate(String canonicalName) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("exact article tag creation requires a transaction");
        }
        int inserted = tagMapper.insertNameIfAbsent(canonicalName, LocalDateTime.now());
        Long tagId = inserted == 1
                ? tagMapper.selectConnectionLastInsertId()
                : tagMapper.selectIdByExactNameForShare(canonicalName);
        if (tagId == null) {
            throw new IllegalStateException("atomically created tag cannot be reloaded");
        }
        return tagId;
    }
}
