package cumt.zongzuo.community.event.outbox;

public class DomainEventConflictException extends IllegalStateException {
    public DomainEventConflictException(String message) {
        super(message);
    }
}
