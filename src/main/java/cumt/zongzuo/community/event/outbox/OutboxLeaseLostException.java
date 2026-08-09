package cumt.zongzuo.community.event.outbox;

public class OutboxLeaseLostException extends IllegalStateException {
    public OutboxLeaseLostException(long outboxId, String leaseOwner) {
        super("OUTBOX_LEASE_LOST: row " + outboxId + " is no longer owned by " + leaseOwner);
    }
}
