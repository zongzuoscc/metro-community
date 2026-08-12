package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.retrieval.ResolvedArticleChunk;
import cumt.zongzuo.community.ai.provider.AiChatResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GroundedAnswerParser {

    private static final int MAX_OUTPUT_BYTES = 32_768;
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,2})]");
    private static final Set<String> ROOT_FIELDS = Set.of("answer", "citations");
    private static final Set<String> CITATION_FIELDS = Set.of("marker", "sourceId", "quote");

    private final ObjectMapper strictMapper;

    public GroundedAnswerParser(ObjectMapper objectMapper) {
        strictMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        strictMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(6).maxDocumentLength(MAX_OUTPUT_BYTES).maxTokenCount(256)
                .maxStringLength(MAX_OUTPUT_BYTES).maxNameLength(64).maxNumberLength(16).build());
    }

    GroundedAgentAnswer parse(AiChatResult result, String expectedModel,
                              List<ResolvedArticleChunk> authorized) {
        return parse(result, expectedModel, authorized, false);
    }

    GroundedAgentAnswer parse(AiChatResult result, String expectedModel,
                              List<ResolvedArticleChunk> authorized,
                              boolean personalContextAvailable) {
        return parse(result, expectedModel, authorized, personalContextAvailable, false);
    }

    /**
     * 校验模型回答及站内引用；只有确实没有授权站内资料时，才允许以显式标签输出通用知识。
     * 这样前端无需猜测信息来源，用户也不会把模型自身知识误认为社区文章结论。
     */
    GroundedAgentAnswer parse(AiChatResult result, String expectedModel,
                              List<ResolvedArticleChunk> authorized,
                              boolean personalContextAvailable,
                              boolean generalKnowledgeAllowed) {
        if (result == null || !"stop".equals(result.finishReason())
                || !expectedModel.equals(result.model()) || result.text() == null
                || result.text().isBlank()
                || result.text().getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw invalid("Provider answer is incomplete or incompatible");
        }
        JsonNode root;
        try {
            root = strictMapper.readTree(result.text());
        } catch (Exception error) {
            throw new InvalidAgentAnswerException("Provider answer is not strict JSON", error);
        }
        if (!exactObject(root, ROOT_FIELDS) || !root.path("answer").isTextual()
                || !root.path("citations").isArray()) {
            throw invalid("Provider answer has an invalid schema");
        }
        String answer = root.path("answer").textValue().strip();
        if (answer.isBlank() || answer.length() > 12_000) {
            throw invalid("Provider answer text is invalid");
        }
        Map<String, ResolvedArticleChunk> sources = new HashMap<>();
        authorized.forEach(source -> sources.put(source.sourceId(), source));
        List<AgentCitation> citations = new ArrayList<>();
        Set<Integer> citationMarkers = new HashSet<>();
        for (JsonNode citation : root.path("citations")) {
            if (!exactObject(citation, CITATION_FIELDS) || !citation.path("marker").isInt()
                    || !citation.path("sourceId").isTextual() || !citation.path("quote").isTextual()) {
                throw invalid("Provider citation has an invalid schema");
            }
            int marker = citation.path("marker").intValue();
            String sourceId = citation.path("sourceId").textValue();
            String quote = citation.path("quote").textValue().strip();
            ResolvedArticleChunk source = sources.get(sourceId);
            if (marker < 1 || marker > 8 || !citationMarkers.add(marker) || source == null
                    || codePoints(quote) < 8 || codePoints(quote) > 240
                    || !normalize(source.bodyText()).contains(normalize(quote))) {
                throw invalid("Provider citation is not grounded in an authorized chunk");
            }
            citations.add(new AgentCitation(marker, sourceId, source.articleId(), source.revisionId(),
                    source.chunkId(), source.title(), quote, "/article/" + source.articleId()));
        }
        if (!personalContextAvailable && citations.isEmpty() && generalKnowledgeAllowed
                && !answer.startsWith("【模型通用知识】")) {
            // 无站内资料、记忆或历史时，回答只能来自模型自身知识。即使用户提示词要求
            // “只回复一句话”而导致供应商遗漏标签，也由可信后端确定性补齐来源说明；
            // 这样既不会让整轮对话失败，也不会把模型知识伪装成社区或个人资料。
            answer = "【模型通用知识】" + answer;
        }
        if ((!personalContextAvailable && citations.isEmpty() && !generalKnowledgeAllowed)
                || citations.size() > 8) {
            throw invalid("Grounded answer must contain citations");
        }
        Set<Integer> answerMarkers = new HashSet<>();
        Matcher matcher = MARKER.matcher(answer);
        while (matcher.find()) {
            answerMarkers.add(Integer.parseInt(matcher.group(1)));
        }
        if (!answerMarkers.equals(citationMarkers)
                || !citationMarkers.equals(java.util.stream.IntStream.rangeClosed(1, citations.size())
                .boxed().collect(java.util.stream.Collectors.toSet()))) {
            throw invalid("Answer markers do not match citations");
        }
        citations.sort(java.util.Comparator.comparingInt(AgentCitation::marker));
        return new GroundedAgentAnswer(answer, citations, result.finishReason());
    }

    private static boolean exactObject(JsonNode node, Set<String> fields) {
        return node != null && node.isObject() && node.size() == fields.size()
                && fields.stream().allMatch(node::has);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static InvalidAgentAnswerException invalid(String message) {
        return new InvalidAgentAnswerException(message);
    }
}
