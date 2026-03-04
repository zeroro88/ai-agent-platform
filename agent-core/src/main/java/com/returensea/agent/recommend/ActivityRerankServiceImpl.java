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
        Set<String> allowedIds = candidates.stream().map(ActivityCandidate::id).filter(Objects::nonNull).collect(Collectors.toSet());
        if (allowedIds.isEmpty()) {
            return List.of();
        }

        String prompt = buildPrompt(candidates, userContext, topK);
        try {
            String response = chatLanguageModel.chat(prompt);
            return parseStrict(candidates, response, allowedIds, topK);
        } catch (Exception e) {
            log.warn("LLM rerank failed, returning original order without reasons: {}", e.getMessage());
            return fallbackOriginalOrder(candidates, topK);
        }
    }

    private String buildPrompt(List<ActivityCandidate> candidates, String userContext, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个活动推荐助手。请根据用户需求，从下面「仅限」的活动中按相关性排序，并给出简短推荐理由（一行内）。\n\n");
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

    private List<RerankedActivity> parseStrict(List<ActivityCandidate> candidates, String response, Set<String> allowedIds, int topK) {
        List<RerankedActivity> result = new ArrayList<>();

        for (String line : response.split("\n")) {
            if (result.size() >= topK) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            var m = LINE_FORMAT.matcher(line);
            if (!m.matches()) continue;
            String id = m.group(1).trim();
            String reason = m.group(2).trim();
            if (!allowedIds.contains(id)) {
                log.debug("Dropping LLM output id (not in candidate set): {}", id);
                continue;
            }
            result.add(new RerankedActivity(id, reason.isEmpty() ? "推荐参加" : reason));
        }

        if (result.isEmpty()) {
            return fallbackOriginalOrder(candidates, topK);
        }
        return result;
    }

    private List<RerankedActivity> fallbackOriginalOrder(List<ActivityCandidate> candidates, int topK) {
        return candidates.stream()
                .limit(topK)
                .map(c -> new RerankedActivity(c.id(), "推荐参加"))
                .toList();
    }
}
