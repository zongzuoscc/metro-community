package cumt.zongzuo.community.account;

import jakarta.validation.constraints.NotBlank;

/** 显式确认文本用于降低设置页误触注销的风险。 */
public record AccountDeletionRequest(@NotBlank String confirmation) {
}
