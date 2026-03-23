package com.returensea.agent.recommend;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityRerankServiceImpl implements ActivityRerankService {

    private static final Pattern LINE_FORMAT = Pattern.compile("^\\s*([^|]+)\\s*\\|\\s*(.*)$");

    private final ChatModel chatLanguageModel;

    @Override
    public List<RerankedActivity> rerankWithReasons(List<ActivityCandidate> candidates, String userContext, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ActivityCandidate> ordered = sortCandidatesNewestFirst(new ArrayList<>(candidates));
        Set<String> allowedIds = ordered.stream().map(ActivityCandidate::id).filter(Objects::nonNull).collect(Collectors.toSet());
        if (allowedIds.isEmpty()) {
            return List.of();
        }

        String prompt = buildPrompt(ordered, userContext, topK);
        try {
            long t0 = System.nanoTime();
            log.info("activityRerank LLM call start: candidates={}, topK={}", ordered.size(), topK);
            String response = chatLanguageModel.chat(prompt);
            long llmMs = (System.nanoTime() - t0) / 1_000_000;
            log.info("activityRerank LLM call done: tookMs={}, responseChars={}", llmMs,
                    response == null ? 0 : response.length());
            List<RerankedActivity> parsed = parseStrictLines(response, allowedIds, topK);
            if (parsed.isEmpty()) {
                return fallbackOriginalOrder(ordered, topK);
            }
            return mergeFillAndSortNewestFirst(ordered, parsed, topK);
        } catch (Exception e) {
            log.warn("LLM rerank failed, returning original order without reasons: {}", e.getMessage());
            return fallbackOriginalOrder(ordered, topK);
        }
    }

    private String buildPrompt(List<ActivityCandidate> candidates, String userContext, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个活动推荐助手。请根据用户需求，从下面「仅限」的活动中按相关性排序，并给出简短推荐理由（一行内）。\n");
        sb.append("候选列表已按活动 ID 从新到旧排列（通常 ID 越大表示越新创建的活动）；在相关性相近时，请优先输出较新的活动，并将其排在输出列表的前面。\n\n");
        sb.append("用户需求/上下文：").append(userContext != null ? userContext : "无").append("\n\n");
        sb.append("候选活动列表（只能从下列 ID 中选取，不得编造其他 ID）：\n");
        for (ActivityCandidate c : candidates) {
            sb.append("- ID: ").append(c.id())
              .append(" | 标题: ").append(c.title())
              .append(" | 城市: ").append(c.city() != null ? c.city() : "")
              .append(" | 简介: ").append(truncate(c.description(), 80)).append("\n");
        }
        sb.append("\n请严格按以下格式输出，每行一条，共最多 ").append(topK).append(" 条：\n");
        sb.append("<活动ID>|<一句话推荐理由>\n");
        sb.append("例如：act-001|与您关注的创业方向匹配，且在北京举办。\n");
        sb.append("只输出上述格式的行，不要输出候选列表中没有的活动 ID。\n");
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 仅解析 LLM 行，不补全、不排序 */
    private List<RerankedActivity> parseStrictLines(String response, Set<String> allowedIds, int topK) {
        List<RerankedActivity> result = new ArrayList<>();
        if (response == null) {
            return result;
        }
        for (String line : response.split("\n")) {
            if (result.size() >= topK) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            var m = LINE_FORMAT.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String id = m.group(1).trim();
            String reason = m.group(2).trim();
            if (!allowedIds.contains(id)) {
                log.debug("Dropping LLM output id (not in candidate set): {}", id);
                continue;
            }
            result.add(new RerankedActivity(id, reason.isEmpty() ? "推荐参加" : reason));
        }
        return result;
    }

    /**
     * 用「较新优先」候选顺序补全 LLM 未覆盖的条目至 topK，再按数值 ID 降序输出（新增活动更靠前）。
     */
    private List<RerankedActivity> mergeFillAndSortNewestFirst(
            List<ActivityCandidate> orderedNewestFirst,
            List<RerankedActivity> parsed,
            int topK) {
        Map<String, String> reasonById = new LinkedHashMap<>();
        for (RerankedActivity r : parsed) {
            reasonById.putIfAbsent(r.id(), r.reason());
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(reasonById.keySet());
        for (ActivityCandidate c : orderedNewestFirst) {
            if (ids.size() >= topK) {
                break;
            }
            ids.add(c.id());
        }
        List<String> sortedIds = new ArrayList<>(ids);
        sortedIds.sort((a, b) -> Long.compare(numericIdForSort(b), numericIdForSort(a)));
        return sortedIds.stream()
                .map(id -> new RerankedActivity(id, reasonById.getOrDefault(id, "推荐参加")))
                .toList();
    }

    private List<ActivityCandidate> sortCandidatesNewestFirst(List<ActivityCandidate> in) {
        List<ActivityCandidate> copy = new ArrayList<>(in);
        copy.sort((a, b) -> Long.compare(numericIdForSort(b.id()), numericIdForSort(a.id())));
        return copy;
    }

    /** 可解析为 long 的 ID 参与排序；非数字 ID（如 act-xxx）排在数字 ID 之后 */
    private static long numericIdForSort(String id) {
        if (id == null || id.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(id.trim(), 10);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private List<RerankedActivity> fallbackOriginalOrder(List<ActivityCandidate> candidates, int topK) {
        return candidates.stream()
                .limit(topK)
                .map(c -> new RerankedActivity(c.id(), "推荐参加"))
                .toList();
    }
}
