package cumt.zongzuo.community.utils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** DFA-based deterministic policy rules with an explicit fail-closed readiness boundary. */
@Slf4j
@Component
public class SensitiveUtils {

    private final Resource dictionary;
    private volatile TrieNode root = new TrieNode();
    private volatile boolean ready;

    public SensitiveUtils() {
        this(new ClassPathResource("sensitive-words.txt"));
    }

    SensitiveUtils(Resource dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
    }

    @PostConstruct
    public void init() {
        ready = false;
        TrieNode loaded = new TrieNode();
        int count = 0;
        try {
            if (!dictionary.exists()) {
                root = loaded;
                log.warn("敏感词库文件不存在，确定性规则未就绪");
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    dictionary.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.strip();
                    if (!word.isEmpty()) {
                        insertWord(loaded, word);
                        count++;
                    }
                }
            }
            root = loaded;
            ready = count > 0;
            if (ready) {
                log.info("敏感词库加载完成，共载入 {} 个词条", count);
            }
            else {
                log.warn("敏感词库为空，确定性规则未就绪");
            }
        }
        catch (Exception error) {
            root = new TrieNode();
            ready = false;
            log.error("敏感词库加载失败", error);
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** Returns offsets only so moderation evidence never persists the dictionary word. */
    public Optional<Match> findFirst(String text) {
        if (!ready) {
            throw new IllegalStateException("deterministic policy rules are not ready");
        }
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        TrieNode snapshot = root;
        for (int start = 0; start < text.length(); start++) {
            TrieNode cursor = snapshot;
            for (int end = start; end < text.length(); end++) {
                cursor = cursor.children.get(text.charAt(end));
                if (cursor == null) {
                    break;
                }
                if (cursor.terminal && end + 1 - start >= 2) {
                    return Optional.of(new Match(start, end + 1));
                }
            }
        }
        return Optional.empty();
    }

    /** Legacy API retained for existing synchronous validation callers. */
    public String check(String text) {
        if (!ready) {
            return null;
        }
        return findFirst(text).map(match -> text.substring(match.start(), match.end())).orElse(null);
    }

    private static void insertWord(TrieNode root, String word) {
        TrieNode cursor = root;
        for (int index = 0; index < word.length(); index++) {
            cursor = cursor.children.computeIfAbsent(word.charAt(index), ignored -> new TrieNode());
        }
        cursor.terminal = true;
    }

    public record Match(int start, int end) {

        public Match {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid sensitive match offsets");
            }
        }
    }

    private static final class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private boolean terminal;
    }
}
