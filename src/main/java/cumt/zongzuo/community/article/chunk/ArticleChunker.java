package cumt.zongzuo.community.article.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class ArticleChunker {

    public static final String PARSER_VERSION = "commonmark-bgem3-v1";
    public static final String TOKEN_ESTIMATOR_VERSION = "cl100k-estimate-v1";
    public static final String DEPENDENCY_FINGERPRINT =
            "4a0382c0faedd75238ac8e9421c82e0767391bfda75f5890b66eb38bbbf81c41";

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final int OVERLAP_TOKEN_CAP = 96;

    private final int maxTokens;
    private final Parser parser;
    private final TextContentRenderer renderer;
    private final Encoding encoding;

    public ArticleChunker(int maxTokens) {
        if (maxTokens < 32) {
            throw new IllegalArgumentException("maxTokens must be at least 32");
        }
        this.maxTokens = maxTokens;
        this.parser = Parser.builder().extensions(EXTENSIONS)
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build();
        this.renderer = TextContentRenderer.builder().extensions(EXTENSIONS).build();
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    public List<ArticleChunkDraft> chunk(long revisionId,
                                         long parserGeneration,
                                         String title,
                                         String markdown) {
        if (revisionId <= 0 || parserGeneration <= 0) {
            throw new IllegalArgumentException("revision and parser generation must be positive");
        }
        Objects.requireNonNull(title, "title");
        String source = normalize(markdown);
        Node document = parser.parse(source);
        List<String> headings = new ArrayList<>();
        List<BlockText> pending = new ArrayList<>();
        List<ArticleChunkDraft> result = new ArrayList<>();
        List<String> pendingHeading = List.of();

        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                flush(revisionId, parserGeneration, title, source, pendingHeading, pending, result);
                pending.clear();
                updateHeadingPath(headings, heading.getLevel(), renderer.render(heading).strip());
                pendingHeading = List.copyOf(headings);
                continue;
            }
            String text = renderer.render(node).strip();
            if (text.isBlank()) {
                continue;
            }
            for (BlockText block : splitOversized(title, pendingHeading, source,
                    block(source, node, text))) {
                List<BlockText> candidate = new ArrayList<>(pending);
                candidate.add(block);
                if (!pending.isEmpty()
                        && tokens(embeddingInput(title, pendingHeading, body(candidate))) > maxTokens) {
                    List<BlockText> overlap = overlapTail(title, pendingHeading, pending);
                    flush(revisionId, parserGeneration, title, source, pendingHeading, pending, result);
                    pending.clear();
                    pending.addAll(overlap);
                    candidate = new ArrayList<>(pending);
                    candidate.add(block);
                    if (tokens(embeddingInput(title, pendingHeading, body(candidate))) > maxTokens) {
                        pending.clear();
                    }
                }
                pending.add(block);
            }
        }
        flush(revisionId, parserGeneration, title, source, pendingHeading, pending, result);
        return List.copyOf(result);
    }

    private void flush(long revisionId,
                       long generation,
                       String title,
                       String source,
                       List<String> headingPath,
                       List<BlockText> blocks,
                       List<ArticleChunkDraft> output) {
        if (blocks.isEmpty()) {
            return;
        }
        String body = body(blocks);
        String chunkHash = sha256(String.join("\n", PARSER_VERSION,
                String.join(" > ", headingPath), body));
        String embeddingInput = embeddingInput(title, headingPath, body);
        String embeddingHash = sha256(embeddingInput);
        int chunkNo = output.size();
        int start = codepointOffset(source, blocks.getFirst().startUtf16());
        int end = codepointOffset(source, blocks.getLast().endUtf16());
        output.add(new ArticleChunkDraft(
                ArticleChunkId.from(revisionId, generation, PARSER_VERSION, chunkNo, chunkHash),
                revisionId, chunkNo, generation, PARSER_VERSION, title, headingPath, body,
                start, end, tokens(embeddingInput), chunkHash, embeddingHash));
    }

    private static BlockText block(String source, Node node, String text) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            throw new IllegalStateException("CommonMark block is missing source spans");
        }
        SourceSpan first = spans.getFirst();
        SourceSpan last = spans.getLast();
        int start = first.getInputIndex();
        int end = Math.addExact(last.getInputIndex(), last.getLength());
        if (start < 0 || end < start || end > source.length()) {
            throw new IllegalStateException("CommonMark source span is out of bounds");
        }
        return new BlockText(text, start, end);
    }

    private int tokens(String value) {
        return encoding.countTokens(value);
    }

    private List<BlockText> overlapTail(String title,
                                        List<String> headingPath,
                                        List<BlockText> blocks) {
        List<BlockText> overlap = new ArrayList<>();
        for (int index = blocks.size() - 1; index >= 0; index--) {
            overlap.addFirst(blocks.get(index));
            if (tokens(embeddingInput(title, headingPath, body(overlap))) > OVERLAP_TOKEN_CAP) {
                overlap.removeFirst();
                break;
            }
        }
        return List.copyOf(overlap);
    }

    private List<BlockText> splitOversized(String title,
                                           List<String> headingPath,
                                           String source,
                                           BlockText block) {
        if (tokens(embeddingInput(title, headingPath, block.text())) <= maxTokens) {
            return List.of(block);
        }
        int totalCodepoints = block.text().codePointCount(0, block.text().length());
        int sourceTextStart = source.indexOf(block.text(), block.startUtf16());
        boolean exactSourceMapping = sourceTextStart >= block.startUtf16()
                && sourceTextStart + block.text().length() <= block.endUtf16();
        List<BlockText> pieces = new ArrayList<>();
        int startCodepoint = 0;
        while (startCodepoint < totalCodepoints) {
            int endCodepoint = largestEnd(title, headingPath, block.text(),
                    startCodepoint, totalCodepoints);
            int startUtf16 = block.text().offsetByCodePoints(0, startCodepoint);
            int endUtf16 = block.text().offsetByCodePoints(0, endCodepoint);
            int sourceStart = exactSourceMapping ? sourceTextStart + startUtf16 : block.startUtf16();
            int sourceEnd = exactSourceMapping ? sourceTextStart + endUtf16 : block.endUtf16();
            pieces.add(new BlockText(block.text().substring(startUtf16, endUtf16),
                    sourceStart, sourceEnd));
            if (endCodepoint == totalCodepoints) {
                break;
            }
            int nextStart = overlapStart(block.text(), startCodepoint, endCodepoint);
            if (nextStart <= startCodepoint) {
                nextStart = Math.addExact(startCodepoint, 1);
            }
            startCodepoint = nextStart;
        }
        return List.copyOf(pieces);
    }

    private int largestEnd(String title,
                           List<String> headingPath,
                           String text,
                           int startCodepoint,
                           int totalCodepoints) {
        int low = Math.addExact(startCodepoint, 1);
        int high = totalCodepoints;
        int best = -1;
        while (low <= high) {
            int middle = low + ((high - low) >>> 1);
            String candidate = codepointSubstring(text, startCodepoint, middle);
            if (tokens(embeddingInput(title, headingPath, candidate)) <= maxTokens) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (best < 0) {
            throw new IllegalArgumentException("chunk token cap is too small for its title and headings");
        }
        return best;
    }

    private int overlapStart(String text, int startCodepoint, int endCodepoint) {
        int overlapCap = Math.min(OVERLAP_TOKEN_CAP, Math.max(1, maxTokens / 4));
        int selected = endCodepoint;
        for (int candidate = endCodepoint - 1; candidate > startCodepoint; candidate--) {
            if (tokens(codepointSubstring(text, candidate, endCodepoint)) > overlapCap) {
                break;
            }
            selected = candidate;
        }
        return selected;
    }

    private static String codepointSubstring(String text, int startCodepoint, int endCodepoint) {
        int startUtf16 = text.offsetByCodePoints(0, startCodepoint);
        int endUtf16 = text.offsetByCodePoints(0, endCodepoint);
        return text.substring(startUtf16, endUtf16);
    }

    private static String body(List<BlockText> blocks) {
        return blocks.stream().map(BlockText::text).reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private static String embeddingInput(String title, List<String> headings, String body) {
        return String.join("\n", title, String.join(" > ", headings), body);
    }

    private static void updateHeadingPath(List<String> headings, int level, String value) {
        int desiredParentCount = Math.max(0, level - 1);
        while (headings.size() > desiredParentCount) {
            headings.removeLast();
        }
        while (headings.size() < desiredParentCount) {
            headings.add("");
        }
        headings.add(value);
    }

    private static int codepointOffset(String source, int utf16Offset) {
        return source.codePointCount(0, utf16Offset);
    }

    private static String normalize(String markdown) {
        return Objects.requireNonNullElse(markdown, "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record BlockText(String text, int startUtf16, int endUtf16) {
    }
}
