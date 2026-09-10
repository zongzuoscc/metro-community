package cumt.zongzuo.community.ai.agent;

import java.util.function.Consumer;

/**
 * 仅提取顶层 answer 字符串的增量正文，JSON 引号尚未闭合时即可显示。
 * 正式结构和引用仍由 GroundedAnswerParser 全量校验；这里不把临时显示当成审核通过。
 * 保留跨网络分片的转义、Unicode 代理对状态，引用原文及其他字段绝不进入正文流。
 */
public final class StreamingAnswerText implements Consumer<String> {
    private final Consumer<String> sink;
    private final StringBuilder key = new StringBuilder();
    private int depth, unicodeDigits, unicodeValue, length;
    private boolean inString, escaped, keyString, answerString, expectKey, expectValue, seen, closed;
    private char highSurrogate;
    public StreamingAnswerText(Consumer<String> sink) { this.sink = sink; }

    @Override public void accept(String fragment) {
        var ready = new StringBuilder();
        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            if (inString) {
                if (unicodeDigits > 0) {
                    int digit = Character.digit(c, 16);
                    if (digit < 0) throw invalid();
                    unicodeValue = unicodeValue * 16 + digit;
                    if (--unicodeDigits == 0) character((char) unicodeValue, ready);
                } else if (escaped) {
                    escaped = false;
                    if (c == 'u') { unicodeDigits = 4; unicodeValue = 0; }
                    else character(switch (c) {
                        case '"', '\\', '/' -> c;
                        case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case 'b' -> '\b'; case 'f' -> '\f'; default -> throw invalid();
                    }, ready);
                } else if (c == '\\') escaped = true;
                else if (c == '"') {
                    if (highSurrogate != 0) throw invalid();
                    inString = false;
                    if (answerString) closed = true;
                    answerString = false;
                } else {
                    if (c < 32) throw invalid();
                    character(c, ready);
                }
            } else if (c == '"') {
                keyString = depth == 1 && expectKey;
                answerString = depth == 1 && expectValue && "answer".contentEquals(key);
                if (answerString) { if (seen) throw invalid(); seen = true; }
                if (keyString) { key.setLength(0); expectKey = false; }
                expectValue = false;
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth == 1 && c == '{') expectKey = true;
                else expectValue = false;
                if (depth > 16) throw invalid();
            } else if (c == '}' || c == ']') depth--;
            else if (depth == 1 && c == ':') expectValue = true;
            else if (depth == 1 && c == ',') { expectKey = true; expectValue = false; }
        }
        if (!ready.isEmpty()) sink.accept(ready.toString());
    }

    private void character(char c, StringBuilder ready) {
        if (keyString) { if (key.length() > 128) throw invalid(); key.append(c); }
        if (!answerString) return;
        if (++length > 12000) throw invalid();
        if (highSurrogate != 0) {
            if (!Character.isLowSurrogate(c)) throw invalid();
            ready.append(highSurrogate).append(c); highSurrogate = 0;
        } else if (Character.isHighSurrogate(c)) highSurrogate = c;
        else if (Character.isLowSurrogate(c)) throw invalid();
        else ready.append(c);
    }

    public void finish() {
        if (!seen || !closed || inString || depth != 0) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid streamed answer JSON"); }
}
