package com.returensea.agent.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作记忆中 lastActivityIds 经 JSON 反序列化后可能是 Integer、Long 等，与报名校验用的 String 不一致会导致 contains 失败。
 */
public final class LastActivityIdsSupport {

    private LastActivityIdsSupport() {}

    public static List<String> normalize(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }
}
