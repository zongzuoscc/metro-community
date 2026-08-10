package cumt.zongzuo.community.ai.moderation.revision;

import java.time.Instant;

record ModerationJobLease(long jobId, long articleId, long revisionId, String contentHash,
                          String owner, long jobLockVersion, long articleLockVersion,
                          long lifecycleEpoch, long authorId, int attemptCount,
                          Instant taskDeadline) {

    ModerationJobLease reserveAttempt() {
        return new ModerationJobLease(jobId, articleId, revisionId, contentHash, owner,
                jobLockVersion + 1, articleLockVersion, lifecycleEpoch, authorId,
                attemptCount + 1, taskDeadline);
    }
}
