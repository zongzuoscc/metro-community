package cumt.zongzuo.community.ai.moderation.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiExecutionException;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.utils.SensitiveUtils;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ArticleModerationWorker {

    private final ArticleModerationStateMachine stateMachine;
    private final AiCapabilityExecutor executor;
    private final AiChatGateway gateway;
    private final MetroAiProperties properties;
    private final SensitiveUtils sensitiveUtils;
    private final ModerationPromptFactory promptFactory;
    private final ModerationOutputParser outputParser;
    private final ModerationChunker chunker;
    private final Clock clock;

    public ArticleModerationWorker(ArticleModerationStateMachine stateMachine,
                                   AiCapabilityExecutor executor,
                                   AiChatGateway gateway,
                                   MetroAiProperties properties,
                                   SensitiveUtils sensitiveUtils,
                                   ObjectMapper objectMapper,
                                   Clock clock) {
        this.stateMachine = stateMachine;
        this.executor = executor;
        this.gateway = gateway;
        this.properties = properties;
        this.sensitiveUtils = sensitiveUtils;
        this.clock = clock;
        this.promptFactory = new ModerationPromptFactory(objectMapper);
        this.outputParser = new ModerationOutputParser(objectMapper);
        MetroAiProperties.ModerationProperties moderation = properties.getModeration();
        this.chunker = new ModerationChunker(new JTokkitTokenCountEstimator(), promptFactory,
                properties.getDeepSeek().getModel(),
                moderation.getMaxChunkTokens(), moderation.getOverlapTokens(),
                moderation.getMaxChunks(), moderation.getMaxEstimatedTokens(),
                moderation.getMaxOutputTokens(),
                properties.getRuntime().getBackgroundMaxAttempts(),
                moderation.getMaxEstimatedCostMicros(),
                moderation.getInputCostMicrosPerMillionTokens(),
                moderation.getOutputCostMicrosPerMillionTokens());
    }

    public ProcessOutcome process(DomainEvent event) {
        if (!moderationAvailable()) {
            stateMachine.routeUnavailable(event, "AI_UNAVAILABLE");
            return ProcessOutcome.COMPLETE;
        }
        Optional<ModerationJobLease> claimed = stateMachine.claim(event);
        if (claimed.isEmpty()) {
            return stateMachine.isBusy(event) ? ProcessOutcome.BUSY : ProcessOutcome.NOOP;
        }
        processClaimed(claimed.get());
        return ProcessOutcome.COMPLETE;
    }

    private void processClaimed(ModerationJobLease initialLease) {
        ModerationJobLease lease = initialLease;
        try {
        if (!stateMachine.validate(lease)) {
            return;
        }
        if (lease.attemptCount() > 0) {
            stateMachine.recordPreProviderFailure(lease, "ATTEMPT_BUDGET_EXHAUSTED");
            return;
        }
        ArticleRevision revision = stateMachine.loadRevision(lease);
        if (revision == null) {
            stateMachine.recordPreProviderFailure(lease, "REVISION_MISSING");
            return;
        }

        String corpus;
        String deterministicError = null;
        try {
            corpus = ModerationCorpusFactory.from(revision);
            if (!sensitiveUtils.isReady()) {
                deterministicError = "DETERMINISTIC_RULE_FAILURE";
            }
            else if (sensitiveUtils.findFirst(corpus).isPresent()) {
                deterministicError = "DETERMINISTIC_POLICY_HIT";
            }
        }
        catch (RuntimeException error) {
            deterministicError = "DETERMINISTIC_RULE_FAILURE";
            corpus = "";
        }
        if (deterministicError != null) {
            stateMachine.recordPreProviderFailure(lease, deterministicError);
            return;
        }
        if (corpus.length() > properties.getModeration().getMaxInputCharacters()) {
            stateMachine.recordPreProviderFailure(lease, "INPUT_TOO_LARGE");
            return;
        }

        final List<ModerationChunk> chunks;
        try {
            ModerationJobLease planningLease = lease;
            chunks = chunker.chunk(revision.getTitle(), corpus,
                    () -> clock.instant().isBefore(planningLease.taskDeadline()));
        }
        catch (IllegalArgumentException | ArithmeticException error) {
            stateMachine.recordPreProviderFailure(lease, chunkError(error));
            return;
        }

        List<ModerationModelOutput> outputs = new ArrayList<>(chunks.size());
        for (ModerationChunk chunk : chunks) {
            ModerationPrompt prompt = promptFactory.create(chunk, properties.getDeepSeek().getModel());
            AtomicReference<ModerationJobLease> leaseCursor = new AtomicReference<>(lease);
            try {
                ModerationCallResult call = executor.execute(new AiInvocationContext(
                                AiCapability.MODERATION, lease.authorId(),
                                "moderation:" + lease.jobId() + ':' + chunk.index(),
                                prompt.inputCharacters(), lease.taskDeadline(), true),
                        attemptObserver(leaseCursor, prompt, chunk),
                        observed -> invokeAndParse(observed.invocation().lease(), prompt, chunk));
                lease = leaseCursor.get();
                outputs.add(call.output());
            }
            catch (RuntimeException error) {
                lease = leaseCursor.get();
                stateMachine.recordPreProviderFailure(lease, errorCode(error));
                return;
            }
        }
        ModerationAggregate aggregate = ModerationAggregate.from(outputs,
                properties.getModeration().getMinimumConfidence());
        stateMachine.finishShadow(lease, aggregate);
        }
        catch (RuntimeException error) {
            if (containsCause(error, DataAccessException.class)
                    || containsCause(error, TransactionException.class)) {
                throw error;
            }
            stateMachine.recordPreProviderFailure(lease, errorCode(error));
        }
    }

    private AiCapabilityExecutor.AttemptObserver<ObservedAttempt, ModerationCallResult>
    attemptObserver(AtomicReference<ModerationJobLease> leaseCursor,
                    ModerationPrompt prompt, ModerationChunk chunk) {
        return new AiCapabilityExecutor.AttemptObserver<>() {
            @Override
            public ObservedAttempt begin() {
                ModerationInvocation invocation = stateMachine.reserveAttempt(leaseCursor.get())
                        .orElseThrow(StaleModerationJobException::new);
                leaseCursor.set(invocation.lease());
                return new ObservedAttempt(invocation, System.nanoTime());
            }

            @Override
            public void complete(ObservedAttempt observed, ModerationCallResult call,
                                 Throwable error) {
                ModerationOutputException outputFailure = findCause(error,
                        ModerationOutputException.class);
                AiChatResult result = call == null
                        ? outputFailure == null ? null : outputFailure.result()
                        : call.result();
                ModerationModelOutput output = call == null ? null : call.output();
                RuntimeException failure = runtime(error);
                ModerationAttemptRecord record = new ModerationAttemptRecord(prompt.inputHash(),
                        chunk, elapsedMillis(observed.startedNanos()), result, output,
                        failure == null ? null : errorCode(failure));
                boolean recorded = failure == null
                        ? stateMachine.recordSuccess(observed.invocation(), record)
                        : stateMachine.recordAttemptFailure(observed.invocation(), record);
                if (!recorded) {
                    throw new StaleModerationJobException();
                }
            }
        };
    }

    private ModerationCallResult invokeAndParse(ModerationJobLease lease,
                                                ModerationPrompt prompt,
                                                ModerationChunk chunk) {
        AiChatResult result = invokeBound(lease, prompt);
        try {
            ModerationModelOutput output = outputParser.parse(result,
                    properties.getDeepSeek().getModel(), prompt.promptVersion(),
                    chunk.content().length());
            return new ModerationCallResult(result, output);
        }
        catch (RuntimeException error) {
            throw new ModerationOutputException(result, error);
        }
    }

    private AiChatResult invokeBound(ModerationJobLease lease, ModerationPrompt prompt) {
        if (!stateMachine.validate(lease)) {
            throw new StaleModerationJobException();
        }
        AiChatResult result = gateway.generate(new AiChatCommand(AiCapability.MODERATION,
                prompt.messages(), AiResponseMode.JSON_OBJECT));
        // The observed completion transaction is the authoritative post-call fence. It
        // records a sanitized attempt before superseding a still-owned stale binding, or
        // writes nothing when this worker has actually lost its lease.
        return result;
    }

    private boolean moderationAvailable() {
        return properties.isCapabilityEnabled(AiCapability.MODERATION)
                && StringUtils.hasText(properties.getDeepSeek().getApiKey())
                && StringUtils.hasText(properties.getDeepSeek().getBaseUrl())
                && StringUtils.hasText(properties.getDeepSeek().getModel());
    }

    private static String errorCode(RuntimeException error) {
        ModerationOutputException outputFailure = findCause(error,
                ModerationOutputException.class);
        if (outputFailure != null && outputFailure.getCause() instanceof RuntimeException cause) {
            return errorCode(cause);
        }
        if (error instanceof AiProviderException provider) {
            return "PROVIDER_" + provider.reason().name();
        }
        if (error instanceof AiExecutionException execution) {
            return "EXECUTION_" + execution.reason().name();
        }
        if (containsCause(error, StaleModerationJobException.class)) {
            return "STALE_REVISION_BINDING";
        }
        if (error instanceof IllegalArgumentException) {
            return "INVALID_MODEL_OUTPUT";
        }
        return "INTERNAL_ERROR";
    }

    private static RuntimeException runtime(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("moderation provider attempt failed", error);
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean containsCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String chunkError(RuntimeException error) {
        String message = error.getMessage();
        if (message != null && message.contains("empty")) {
            return "EMPTY_CONTENT";
        }
        if (message != null && message.contains("chunk cap")) {
            return "CHUNK_CAP_EXCEEDED";
        }
        if (message != null && message.contains("token cap")) {
            return "TOKEN_CAP_EXCEEDED";
        }
        if (message != null && message.contains("cost cap")) {
            return "COST_CAP_EXCEEDED";
        }
        if (message != null && message.contains("deadline")) {
            return "TASK_DEADLINE_EXCEEDED";
        }
        return "CHUNKING_FAILED";
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    public enum ProcessOutcome {
        COMPLETE,
        NOOP,
        BUSY
    }

    private record ObservedAttempt(ModerationInvocation invocation, long startedNanos) {
    }

    private record ModerationCallResult(AiChatResult result, ModerationModelOutput output) {
    }

    private static final class ModerationOutputException extends IllegalArgumentException {

        private final AiChatResult result;

        private ModerationOutputException(AiChatResult result, RuntimeException cause) {
            super("moderation model output was invalid", cause);
            this.result = result;
        }

        private AiChatResult result() {
            return result;
        }
    }
}
