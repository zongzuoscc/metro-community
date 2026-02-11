package cumt.zongzuo.community.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 敏感词过滤工具类 (基于 DFA 算法)
 */
@Slf4j
@Component
public class SensitiveUtils {

    // DFA 词库树根节点
    private Map<Object, Object> sensitiveWordMap;

    /**
     * 初始化：在 Bean 创建完成后自动加载词库文件
     */
    @PostConstruct
    public void init() {
        try {
            // 读取 classpath 下的 sensitive-words.txt
            ClassPathResource resource = new ClassPathResource("sensitive-words.txt");
            if (!resource.exists()) {
                log.warn("敏感词库文件不存在，跳过加载");
                sensitiveWordMap = new HashMap<>();
                return;
            }

            InputStream inputStream = resource.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            sensitiveWordMap = new HashMap<>();
            String line;
            int count = 0;

            // 逐行读取并构建 Trie 树
            while ((line = reader.readLine()) != null) {
                if (line != null && !line.trim().isEmpty()) {
                    insertWord(line.trim());
                    count++;
                }
            }
            log.info("敏感词库加载完成，共载入 {} 个词条", count);
            reader.close();
        } catch (Exception e) {
            log.error("敏感词库加载失败", e);
            // 防止空指针，初始化空 map
            sensitiveWordMap = new HashMap<>();
        }
    }

    /**
     * 构建 DFA 树 (辅助方法)
     */
    private void insertWord(String word) {
        Map<Object, Object> nowMap = sensitiveWordMap;
        for (int i = 0; i < word.length(); i++) {
            char keyChar = word.charAt(i);
            Object tempMap = nowMap.get(keyChar);
            if (tempMap == null) {
                Map<Object, Object> newMap = new HashMap<>();
                newMap.put("isEnd", "0"); // 不是结尾
                nowMap.put(keyChar, newMap);
                nowMap = newMap;
            } else {
                nowMap = (Map<Object, Object>) tempMap;
            }
            if (i == word.length() - 1) {
                nowMap.put("isEnd", "1"); // 是结尾
            }
        }
    }

    /**
     * 检查文本是否包含敏感词
     * @param text 待检测文本
     * @return 返回找到的第一个敏感词；如果未发现，返回 null
     */
    public String check(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        if (sensitiveWordMap == null || sensitiveWordMap.isEmpty()) return null;

        for (int i = 0; i < text.length(); i++) {
            int matchLen = checkWord(text, i);
            if (matchLen > 0) {
                return text.substring(i, i + matchLen);
            }
        }
        return null;
    }

    /**
     * 检查从 beginIndex 开始的子串是否匹配敏感词
     */
    private int checkWord(String text, int beginIndex) {
        boolean flag = false;
        int matchLen = 0;
        Map<Object, Object> nowMap = sensitiveWordMap;

        for (int i = beginIndex; i < text.length(); i++) {
            char word = text.charAt(i);
            nowMap = (Map<Object, Object>) nowMap.get(word);
            if (nowMap != null) {
                matchLen++;
                if ("1".equals(nowMap.get("isEnd"))) {
                    flag = true; // 匹配到了完整词
                }
            } else {
                break; // 不匹配，直接跳出
            }
        }
        // 只有匹配到完整词(flag=true)且长度>1(避免单字误杀)才算
        if (matchLen < 2 || !flag) {
            matchLen = 0;
        }
        return matchLen;
    }
}