package cumt.zongzuo.community.event.outbox;

public record DomainEventRetentionResult(
        int publishedDeleted,
        int requeuedPublishedDeleted,
        int resolvedDeadDeleted,
        int inboxDeleted,
        int resolvedMigrationIssueDeleted) {
}
