package cumt.zongzuo.community.account;

import java.time.LocalDateTime;

/** 返回给设置页的最小注销状态，不包含密码、邮箱等账号隐私。 */
public record AccountDeletionStatus(
        AccountState state,
        LocalDateTime requestedAt,
        LocalDateTime purgeAfter) {
}
