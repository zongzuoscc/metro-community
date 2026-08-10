package cumt.zongzuo.community.event.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class DomainEventDeadLetterOperator {

    private static final Pattern OPERATOR = Pattern.compile("[A-Za-z0-9._:@-]{1,96}");

    private final DomainEventOutboxMapper mapper;

    public DomainEventDeadLetterOperator(DomainEventOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void acknowledgeDead(long id, String operator) {
        String actor = validateOperator(operator);
        if (mapper.acknowledgeDeadExact(id, actor) != 1) {
            throw new IllegalStateException("dead-letter acknowledgement CAS conflict");
        }
    }

    @Transactional
    public void requeueDead(long id, String operator) {
        String actor = validateOperator(operator);
        if (mapper.requeueDeadExact(id, actor) != 1) {
            throw new IllegalStateException("dead-letter requeue CAS conflict");
        }
    }

    private static String validateOperator(String operator) {
        String actor = Objects.requireNonNull(operator, "operator");
        if (!OPERATOR.matcher(actor).matches()) {
            throw new IllegalArgumentException("operator must be a stable 1-96 character identifier");
        }
        return actor;
    }
}
