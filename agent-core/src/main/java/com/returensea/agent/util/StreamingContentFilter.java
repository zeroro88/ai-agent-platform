package com.returensea.agent.util;

import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

/**
 * 流式输出过滤：在逐 token 推送时缓冲并剔除 &lt;tool_call&gt;...&lt;/tool_call&gt; 整块，
 * 避免原始工具调用 JSON 下发给前端；其余内容经 ContentSanitizer 后再下发。
 * 每个流使用独立实例，流结束后需调用 {@link #flush()} 将剩余缓冲写出。
 */
@RequiredArgsConstructor
public class StreamingContentFilter {

    private static final String TOOL_CALL_OPEN = "<tool_call>";
    private static final String TOOL_CALL_CLOSE = "</tool_call>";

    private final ContentSanitizer contentSanitizer;
    private final Consumer<String> downstream;

    private final StringBuilder buffer = new StringBuilder();
    private boolean insideToolCall = false;

    /**
     * 将本段内容加入缓冲，解析并只将「非 tool_call 块」的文本经护栏后推给 downstream。
     */
    public void accept(String chunk) {
        if (chunk == null) return;
        buffer.append(chunk);
        drain();
    }

    /**
     * 流结束时调用，将缓冲中剩余内容写出（可能包含未闭合的 &lt;tool_call&gt; 片段，会原样输出）。
     */
    public void flush() {
        if (buffer.length() == 0) return;
        String rest = buffer.toString();
        buffer.setLength(0);
        insideToolCall = false;
        if (!rest.isEmpty()) {
            downstream.accept(contentSanitizer.stripSpuriousTokens(rest));
        }
    }

    private void drain() {
        while (true) {
            if (insideToolCall) {
                int closeIdx = buffer.indexOf(TOOL_CALL_CLOSE);
                if (closeIdx >= 0) {
                    buffer.delete(0, closeIdx + TOOL_CALL_CLOSE.length());
                    insideToolCall = false;
                    continue;
                }
                // 可能结尾是 "</tool_call>" 的不完整前缀，保留该后缀
                int keepFrom = findStartOfPartial(buffer, TOOL_CALL_CLOSE);
                if (keepFrom > 0) {
                    buffer.delete(0, keepFrom);
                }
                return;
            }
            int openIdx = buffer.indexOf(TOOL_CALL_OPEN);
            if (openIdx >= 0) {
                String before = buffer.substring(0, openIdx);
                buffer.delete(0, openIdx + TOOL_CALL_OPEN.length());
                insideToolCall = true;
                if (!before.isEmpty()) {
                    downstream.accept(contentSanitizer.stripSpuriousTokens(before));
                }
                continue;
            }
            // 缓冲末尾可能是不完整的 "<tool_call>"
            int lastOpen = buffer.lastIndexOf("<");
            if (lastOpen >= 0 && lastOpen + TOOL_CALL_OPEN.length() > buffer.length()) {
                String before = buffer.substring(0, lastOpen);
                buffer.delete(0, lastOpen);
                if (!before.isEmpty()) {
                    downstream.accept(contentSanitizer.stripSpuriousTokens(before));
                }
            } else if (lastOpen < 0) {
                String all = buffer.toString();
                buffer.setLength(0);
                if (!all.isEmpty()) {
                    downstream.accept(contentSanitizer.stripSpuriousTokens(all));
                }
            }
            return;
        }
    }

    /** 返回可能构成 tag 的不完整前缀的起始下标（保留最长匹配后缀）。 */
    private static int findStartOfPartial(StringBuilder buf, String tag) {
        int len = buf.length();
        int maxK = Math.min(tag.length(), len);
        for (int k = maxK; k >= 1; k--) {
            if (tag.startsWith(buf.substring(len - k))) {
                return len - k;
            }
        }
        return 0;
    }
}
