package cumt.zongzuo.community.ai.moderation.revision;

import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plans the complete article before any Provider traffic is allowed. */
public final class ModerationChunker {

    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");
    private static final int TOKEN_SAFETY_NUMERATOR = 5;
    private static final int TOKEN_SAFETY_DENOMINATOR = 4;
    private static final int MESSAGE_FRAMING_RESERVE = 16;

    private final TokenCountEstimator estimator;
    private final ModerationPromptFactory promptFactory;
    private final String model;
    private final int maxChunkTokens;
    private final int overlapTokens;
    private final int maxChunks;
    private final int maxTotalTokens;
    private final int reservedOutputTokens;
    private final int maxProviderAttempts;
    private final long maxEstimatedCostMicros;
    private final long inputCostMicrosPerMillionTokens;
    private final long outputCostMicrosPerMillionTokens;

    public ModerationChunker(TokenCountEstimator estimator, ModerationPromptFactory promptFactory,
                             String model, int maxChunkTokens, int overlapTokens,
                             int maxChunks, int maxTotalTokens, int reservedOutputTokens,
                             int maxProviderAttempts) {
        this(estimator, promptFactory, model, maxChunkTokens, overlapTokens, maxChunks,
                maxTotalTokens, reservedOutputTokens, maxProviderAttempts,
                Long.MAX_VALUE / 4, 1, 1);
    }

    public ModerationChunker(TokenCountEstimator estimator,
                             ModerationPromptFactory promptFactory,
                             String model, int maxChunkTokens, int overlapTokens,
                             int maxChunks, int maxTotalTokens, int reservedOutputTokens,
                             int maxProviderAttempts, long maxEstimatedCostMicros,
                             long inputCostMicrosPerMillionTokens,
                             long outputCostMicrosPerMillionTokens) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("moderation model must not be blank");
        }
        if (maxChunkTokens <= 0 || overlapTokens < 0 || overlapTokens >= maxChunkTokens
                || maxChunks <= 0 || maxTotalTokens <= 0 || reservedOutputTokens <= 0
                || maxProviderAttempts <= 0 || maxEstimatedCostMicros <= 0
                || inputCostMicrosPerMillionTokens <= 0
                || outputCostMicrosPerMillionTokens <= 0) {
            throw new IllegalArgumentException("invalid moderation chunk limits");
        }
        this.model = model;
        this.maxChunkTokens = maxChunkTokens;
        this.overlapTokens = overlapTokens;
        this.maxChunks = maxChunks;
        this.maxTotalTokens = maxTotalTokens;
        this.reservedOutputTokens = reservedOutputTokens;
        this.maxProviderAttempts = maxProviderAttempts;
        this.maxEstimatedCostMicros = maxEstimatedCostMicros;
        this.inputCostMicrosPerMillionTokens = inputCostMicrosPerMillionTokens;
        this.outputCostMicrosPerMillionTokens = outputCostMicrosPerMillionTokens;
    }

    public List<ModerationChunk> chunk(String title, String body) {
        return chunk(title, body, () -> true);
    }

    List<ModerationChunk> chunk(String title, String body, BooleanSupplier withinDeadline) {
        Objects.requireNonNull(withinDeadline, "withinDeadline");
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("moderation content must not be empty");
        }
        requireWithinDeadline(withinDeadline);
        String safeTitle = title == null || title.isBlank() ? "(untitled)" : title.strip();
        List<ModerationChunk> planned = new ArrayList<>();
        int totalTokens = 0;
        long totalCostMicros = 0L;
        int start = firstNonWhitespace(body, 0);
        while (start < body.length()) {
            requireWithinDeadline(withinDeadline);
            if (planned.size() >= maxChunks) {
                throw new IllegalArgumentException("moderation chunk cap exceeded");
            }
            String headingPath = headingPath(safeTitle, body, start);
            int end = largestEnd(body, start, headingPath, withinDeadline);
            if (end <= start) {
                throw new IllegalArgumentException("moderation chunk token budget is too small");
            }
            String content = body.substring(start, end);
            int estimated = estimate(headingPath, content);
            int worstCaseAttemptTokens = Math.multiplyExact(
                    Math.addExact(estimated, reservedOutputTokens), maxProviderAttempts);
            totalTokens = Math.addExact(totalTokens, worstCaseAttemptTokens);
            if (totalTokens > maxTotalTokens) {
                throw new IllegalArgumentException("moderation whole-task token cap exceeded");
            }
            long perAttemptCost = Math.addExact(
                    ceilingCost(estimated, inputCostMicrosPerMillionTokens),
                    ceilingCost(reservedOutputTokens, outputCostMicrosPerMillionTokens));
            totalCostMicros = Math.addExact(totalCostMicros,
                    Math.multiplyExact(perAttemptCost, maxProviderAttempts));
            if (totalCostMicros > maxEstimatedCostMicros) {
                throw new IllegalArgumentException("moderation whole-task cost cap exceeded");
            }
            planned.add(new ModerationChunk(planned.size(), headingPath, content,
                    start, end, estimated));
            if (end >= body.length()) {
                break;
            }
            int next = overlapStart(body, start, end);
            if (next <= start) {
                next = Character.offsetByCodePoints(body, start, 1);
            }
            start = firstNonWhitespace(body, next);
        }
        if (planned.isEmpty()) {
            throw new IllegalArgumentException("moderation content must not be empty");
        }
        return List.copyOf(planned);
    }

    private int largestEnd(String body, int start, String headingPath,
                           BooleanSupplier withinDeadline) {
        int low = 1;
        int high = body.codePointCount(start, body.length());
        int best = start;
        while (low <= high) {
            requireWithinDeadline(withinDeadline);
            int midpoint = low + (high - low) / 2;
            int mid = body.offsetByCodePoints(start, midpoint);
            int tokens = estimate(headingPath, body.substring(start, mid));
            if (tokens <= maxChunkTokens) {
                best = mid;
                low = midpoint + 1;
            } else {
                high = midpoint - 1;
            }
        }
        return best;
    }

    private int overlapStart(String body, int chunkStart, int end) {
        if (overlapTokens == 0) {
            return end;
        }
        int cursor = end;
        while (cursor > chunkStart) {
            int previous = Character.offsetByCodePoints(body, cursor, -1);
            cursor = previous;
            if (estimator.estimate(body.substring(cursor, end)) >= overlapTokens) {
                return cursor;
            }
        }
        return chunkStart;
    }

    private int estimate(String headingPath, String content) {
        ModerationPrompt prompt = promptFactory.create(new ModerationChunk(0, headingPath,
                content, 0, content.length(), 0), model);
        int raw = 0;
        for (var message : prompt.messages()) {
            raw = Math.addExact(raw, estimator.estimate(
                    "ROLE=" + message.role().name() + "\n" + message.text()));
        }
        long scaled = Math.addExact(Math.multiplyExact((long) raw, TOKEN_SAFETY_NUMERATOR),
                TOKEN_SAFETY_DENOMINATOR - 1L) / TOKEN_SAFETY_DENOMINATOR;
        return Math.addExact(Math.toIntExact(scaled), MESSAGE_FRAMING_RESERVE);
    }

    private static String headingPath(String title, String body, int offset) {
        String[] levels = new String[6];
        Matcher matcher = HEADING.matcher(body);
        while (matcher.find() && matcher.start() <= offset) {
            int level = matcher.group(1).length() - 1;
            levels[level] = matcher.group(2).strip();
            for (int deeper = level + 1; deeper < levels.length; deeper++) {
                levels[deeper] = null;
            }
        }
        List<String> path = new ArrayList<>();
        path.add(title);
        for (String heading : levels) {
            if (heading != null && !heading.isBlank()) {
                path.add(heading);
            }
        }
        return String.join(" > ", path);
    }

    private static int firstNonWhitespace(String text, int from) {
        int cursor = from;
        while (cursor < text.length()) {
            int codePoint = text.codePointAt(cursor);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            cursor += Character.charCount(codePoint);
        }
        return cursor;
    }

    private static void requireWithinDeadline(BooleanSupplier withinDeadline) {
        if (!withinDeadline.getAsBoolean()) {
            throw new IllegalArgumentException("moderation task deadline elapsed during planning");
        }
    }

    private static long ceilingCost(long tokens, long microsPerMillionTokens) {
        return Math.addExact(Math.multiplyExact(tokens, microsPerMillionTokens),
                999_999L) / 1_000_000L;
    }
}
