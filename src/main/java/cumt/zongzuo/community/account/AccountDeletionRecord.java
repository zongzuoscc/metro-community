package cumt.zongzuo.community.account;

import java.time.LocalDateTime;

/** Mapper 加锁后返回的内部状态，用 version 约束每一次状态迁移。 */
public record AccountDeletionRecord(
        long userId,
        AccountState state,
        LocalDateTime requestedAt,
        LocalDateTime purgeAfter,
        long version) {

    AccountDeletionStatus status() {
        return new AccountDeletionStatus(state, requestedAt, purgeAfter);
    }
}
