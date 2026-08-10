package cumt.zongzuo.community.ai.moderation.revision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.provider.AiChatResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Dedicated fail-closed reader for untrusted Provider JSON. */
public final class ModerationOutputParser {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_OUTPUT_BYTES = 16_384;
    private static final Set<String> OUTPUT_FIELDS = Set.of("decision", "categories", "severity",
            "confidence", "evidenceOffsets", "reason", "model", "promptVersion");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("start", "end");

    private final ObjectMapper strictMapper;

    public ModerationOutputParser(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.strictMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(8)
                .maxDocumentLength(MAX_OUTPUT_BYTES)
                .maxTokenCount(256)
                .maxStringLength(MAX_OUTPUT_BYTES)
                .maxNameLength(64)
                .maxNumberLength(64)
                .build());
    }

    public ModerationModelOutput parse(AiChatResult result, String expectedModel,
                                       String expectedPromptVersion, int contentLength) {
        Objects.requireNonNull(result, "result");
        requireText(expectedModel, "expectedModel");
        requireText(expectedPromptVersion, "expectedPromptVersion");
        if (contentLength < 0) {
            throw invalid("content length is negative");
        }
        if (!"stop".equals(result.finishReason())) {
            throw invalid("finish reason is not a complete success");
        }
        if (!expectedModel.equals(result.model())) {
            throw invalid("Provider model does not match the request");
        }
        if (result.text() == null || result.text().isBlank()) {
            throw invalid("Provider output is blank");
        }
        if (result.text().getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw invalid("Provider output exceeds its safe size limit");
        }

        ModerationModelOutput output;
        try {
            JsonNode tree = strictMapper.readTree(result.text());
            validateShape(tree);
            output = strictMapper.treeToValue(tree, ModerationModelOutput.class);
        }
        catch (JsonProcessingException error) {
            throw invalid("Provider output is not a strict moderation JSON object", error);
        }
        validate(output, expectedModel, expectedPromptVersion, contentLength);
        return new ModerationModelOutput(output.decision(), Set.copyOf(output.categories()),
                output.severity(), output.confidence(), List.copyOf(output.evidenceOffsets()),
                output.reason(), output.model(), output.promptVersion());
    }

    private static void validateShape(JsonNode tree) {
        if (tree == null || !tree.isObject() || tree.size() != OUTPUT_FIELDS.size()
                || !OUTPUT_FIELDS.stream().allMatch(tree::has)) {
            throw invalid("Provider output must contain exactly the moderation schema fields");
        }
        if (!tree.path("decision").isTextual() || !tree.path("categories").isArray()
                || !tree.path("severity").isIntegralNumber()
                || !tree.path("severity").canConvertToInt()
                || !tree.path("confidence").isNumber()
                || !tree.path("evidenceOffsets").isArray()
                || !tree.path("reason").isTextual()
                || !tree.path("model").isTextual()
                || !tree.path("promptVersion").isTextual()) {
            throw invalid("Provider output contains a missing, null, or coerced field");
        }
        Set<String> categories = new HashSet<>();
        for (JsonNode category : tree.path("categories")) {
            if (!category.isTextual() || !categories.add(category.textValue())) {
                throw invalid("Provider output contains an invalid or duplicate category");
            }
        }
        for (JsonNode evidence : tree.path("evidenceOffsets")) {
            if (!evidence.isObject() || evidence.size() != EVIDENCE_FIELDS.size()
                    || !EVIDENCE_FIELDS.stream().allMatch(evidence::has)
                    || !evidence.path("start").isIntegralNumber()
                    || !evidence.path("start").canConvertToInt()
                    || !evidence.path("end").isIntegralNumber()
                    || !evidence.path("end").canConvertToInt()) {
                throw invalid("Provider output contains invalid evidence shape");
            }
        }
    }

    private static void validate(ModerationModelOutput output, String expectedModel,
                                 String expectedPromptVersion, int contentLength) {
        if (output == null || output.decision() == null || output.categories() == null
                || output.confidence() == null || output.evidenceOffsets() == null) {
            throw invalid("Provider output omitted a required moderation field");
        }
        if (output.categories().contains(null)) {
            throw invalid("Provider output contains invalid policy categories");
        }
        if (output.severity() < 0 || output.severity() > 4) {
            throw invalid("severity is outside [0,4]");
        }
        if (output.confidence().compareTo(ZERO) < 0 || output.confidence().compareTo(ONE) > 0) {
            throw invalid("confidence is outside [0,1]");
        }
        requireText(output.reason(), "reason");
        if (output.reason().length() > MAX_REASON_LENGTH) {
            throw invalid("reason exceeds its safe storage limit");
        }
        if (!expectedModel.equals(output.model()) || !expectedPromptVersion.equals(output.promptVersion())) {
            throw invalid("model or prompt version does not match the request");
        }
        for (ModerationEvidence evidence : output.evidenceOffsets()) {
            if (evidence == null || evidence.start() < 0 || evidence.end() <= evidence.start()
                    || evidence.end() > contentLength) {
                throw invalid("evidence offset is outside the supplied chunk");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
