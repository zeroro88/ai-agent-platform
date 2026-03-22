package com.returensea.agent.slot;

import com.returensea.agent.memory.MemoryService;
import com.returensea.agent.util.LastActivityIdsSupport;
import com.returensea.agent.util.RegistrationParsers;
import com.returensea.common.model.SlotDefinition;
import com.returensea.common.model.SlotFillingRequest;
import com.returensea.common.model.SlotFillingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotFillingServiceImpl implements SlotFillingService {

    private final MemoryService memoryService;
    private final Map<String, List<SlotDefinition>> slotDefinitions = new HashMap<>();

    @jakarta.annotation.PostConstruct
    public void init() {
        initializeDefaultSlotDefinitions();
    }

    private void initializeDefaultSlotDefinitions() {
        registerSlotDefinitions("ACTIVITY_SEARCH", List.of(
            SlotDefinition.builder()
                .name("city")
                .description("城市")
                .type("string")
                .required(false)
                .validValues(List.of("北京", "上海", "深圳", "广州", "杭州", "成都"))
                .promptTemplate("请问您想查询哪个城市的活动？")
                .build(),
            SlotDefinition.builder()
                .name("keyword")
                .description("关键词")
                .type("string")
                .required(false)
                .promptTemplate("请问您想搜索什么主题的活动？")
                .build()
        ));

        registerSlotDefinitions("ACTIVITY_REGISTER", List.of(
            SlotDefinition.builder()
                .name("activityId")
                .description("活动ID")
                .type("string")
                .required(true)
                .promptTemplate("请提供您要报名的活动ID。")
                .build(),
            SlotDefinition.builder()
                .name("name")
                .description("姓名")
                .type("string")
                .required(true)
                .promptTemplate("请提供您的姓名。")
                .build(),
            SlotDefinition.builder()
                .name("phone")
                .description("手机号")
                .type("string")
                .required(true)
                .promptTemplate("请提供您的手机号码。")
                .build(),
            SlotDefinition.builder()
                .name("email")
                .description("邮箱")
                .type("string")
                .required(false)
                .promptTemplate("请提供您的邮箱地址（可选）。")
                .build()
        ));

        registerSlotDefinitions("POLICY_QUERY", List.of(
            SlotDefinition.builder()
                .name("city")
                .description("城市")
                .type("string")
                .required(false)
                .validValues(List.of("北京", "上海", "深圳", "广州", "杭州", "成都", "全国"))
                .promptTemplate("请问您想查询哪个城市或地区的政策？")
                .build(),
            SlotDefinition.builder()
                .name("category")
                .description("政策类别")
                .type("string")
                .required(false)
                .validValues(List.of("落户", "创业", "就业", "购房", "购车", "补贴", "教育"))
                .promptTemplate("请问您想了解哪类政策？")
                .build()
        ));
    }

    @Override
    public SlotFillingResult fillSlots(SlotFillingRequest request) {
        String userMessage = request.getUserMessage();
        Map<String, Object> currentSlots = request.getCurrentSlots() != null 
            ? new HashMap<>(request.getCurrentSlots()) 
            : new HashMap<>();
        List<SlotDefinition> requiredSlots = request.getRequiredSlots();

        if (requiredSlots == null || requiredSlots.isEmpty()) {
            requiredSlots = slotDefinitions.get(request.getIntentType());
        }

        if (requiredSlots == null) {
            return SlotFillingResult.builder()
                .filledSlots(currentSlots)
                .missingSlots(Collections.emptyList())
                .isComplete(true)
                .build();
        }

        for (SlotDefinition slot : requiredSlots) {
            if (currentSlots.containsKey(slot.getName()) && currentSlots.get(slot.getName()) != null) {
                continue;
            }

            Object extractedValue = extractSlotValue(slot, userMessage, request.getSessionId(), request.getUserId());
            if (extractedValue != null) {
                currentSlots.put(slot.getName(), extractedValue);
            }
        }

        List<String> missingSlots = requiredSlots.stream()
            .filter(slot -> !currentSlots.containsKey(slot.getName()) || currentSlots.get(slot.getName()) == null)
            .filter(SlotDefinition::isRequired)
            .map(SlotDefinition::getName)
            .collect(Collectors.toList());

        String clarificationMessage = null;
        if (!missingSlots.isEmpty()) {
            SlotDefinition nextSlot = requiredSlots.stream()
                .filter(s -> missingSlots.contains(s.getName()))
                .findFirst()
                .orElse(null);
            
            if (nextSlot != null && nextSlot.getPromptTemplate() != null) {
                clarificationMessage = nextSlot.getPromptTemplate();
            }
        }

        return SlotFillingResult.builder()
            .filledSlots(currentSlots)
            .missingSlots(missingSlots)
            .isComplete(missingSlots.isEmpty())
            .clarificationMessage(clarificationMessage)
            .build();
    }

    private Object extractSlotValue(SlotDefinition slot, String userMessage, String sessionId, String userId) {
        String slotName = slot.getName().toLowerCase();

        switch (slotName) {
            case "city":
                return extractCity(userMessage);
            case "category":
                return extractCategory(userMessage);
            case "phone":
                return extractPhone(userMessage);
            case "email":
                return extractEmail(userMessage);
            case "name":
                return extractName(userMessage);
            case "activityid":
                return extractActivityId(userMessage, sessionId, userId);
            case "keyword":
                return extractKeyword(userMessage);
            default:
                return null;
        }
    }

    private String extractCity(String text) {
        List<String> cities = List.of("北京", "上海", "深圳", "广州", "杭州", "成都", "南京", "武汉", "西安", "重庆");
        for (String city : cities) {
            if (text.contains(city)) {
                return city;
            }
        }
        return null;
    }

    private String extractCategory(String text) {
        List<String> categories = List.of("落户", "创业", "就业", "购房", "购车", "补贴", "教育", "落户政策", "创业扶持", "生活补贴");
        for (String category : categories) {
            if (text.contains(category)) {
                return category.replace("政策", "").replace("扶持", "").replace("生活", "");
            }
        }
        return null;
    }

    private String extractPhone(String text) {
        return RegistrationParsers.extractPhone(text);
    }

    private String extractEmail(String text) {
        Pattern pattern = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private String extractName(String text) {
        String parsed = RegistrationParsers.extractName(text);
        if (parsed != null) {
            return parsed;
        }
        String[] parts = text.split("[，。,\\.]");
        for (String part : parts) {
            if (part.contains("我叫") || part.contains("姓名")) {
                String s = part.replaceAll(".*叫", "").replace("姓名", "").replaceAll("[:：]", "").trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private String extractActivityId(String text, String sessionId, String userId) {
        if (text == null) return null;
        String trimmed = text.trim();

        Matcher actMatcher = Pattern.compile("act-\\d+").matcher(text);
        if (actMatcher.find()) {
            return actMatcher.group();
        }
        // 整句里的「活动id：1」「活动 ID：28」
        Matcher idInSentence = Pattern.compile("活动\\s*[Ii][Dd]?\\s*[:：\\s]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (idInSentence.find()) {
            return resolveActivityIdDigits(idInSentence.group(1), sessionId, userId);
        }
        Matcher numLabel = Pattern.compile("编号\\s*[:：]?\\s*(\\d+)").matcher(text);
        if (numLabel.find()) {
            return resolveActivityIdDigits(numLabel.group(1), sessionId, userId);
        }
        Matcher ordinalNum = Pattern.compile("第\\s*(\\d+)\\s*个").matcher(text);
        if (ordinalNum.find()) {
            return resolveActivityIdDigits(ordinalNum.group(1).trim(), sessionId, userId);
        }
        Matcher ordinalZh = Pattern.compile("第\\s*([一二三四五六七八九十两]+)\\s*个").matcher(text);
        if (ordinalZh.find()) {
            int n = RegistrationParsers.chineseOrdinalToInt(ordinalZh.group(1).trim());
            if (n > 0) {
                return resolveActivityIdDigits(String.valueOf(n), sessionId, userId);
            }
        }

        // 解析「1」「2」「第一个」「报名第一个」等为最近一次活动列表中的序号（整句仅为数字时）
        int index = parseOrdinalSelection(trimmed);
        if (index >= 0 && sessionId != null && userId != null) {
            List<String> lastIds = getLastActivityIds(sessionId, userId);
            if (lastIds != null && index < lastIds.size()) {
                String resolved = lastIds.get(index);
                if (trimmed.matches("\\d+") && lastIds.contains(trimmed) && !trimmed.equals(resolved)) {
                    log.debug("Resolved selection '{}' as literal activityId (not ordinal)", trimmed);
                    return trimmed;
                }
                log.debug("Resolved selection '{}' -> activityId {}", trimmed, resolved);
                return resolved;
            }
        }
        if (trimmed.matches("\\d+") && sessionId != null && userId != null) {
            List<String> lastIds = getLastActivityIds(sessionId, userId);
            if (lastIds != null && lastIds.contains(trimmed)) {
                return trimmed;
            }
        }
        return null;
    }

    /** 将一串数字解析为「第几项」或字面活动主键（依赖 lastActivityIds） */
    private String resolveActivityIdDigits(String digits, String sessionId, String userId) {
        if (digits == null || sessionId == null || userId == null) {
            return null;
        }
        String trimmed = digits.trim();
        int index = parseOrdinalSelection(trimmed);
        List<String> lastIds = getLastActivityIds(sessionId, userId);
        if (lastIds == null || lastIds.isEmpty()) {
            return null;
        }
        if (index >= 0 && index < lastIds.size()) {
            String resolved = lastIds.get(index);
            if (trimmed.matches("\\d+") && lastIds.contains(trimmed) && !trimmed.equals(resolved)) {
                return trimmed;
            }
            return resolved;
        }
        if (trimmed.matches("\\d+") && lastIds.contains(trimmed)) {
            return trimmed;
        }
        return null;
    }

    /** 解析用户输入的序号：1、2、第一个、报名第一个、报名1 等，返回 0-based 下标，无法解析返回 -1 */
    private int parseOrdinalSelection(String text) {
        if (text == null || text.isEmpty()) return -1;
        String t = text.trim();
        if (t.matches("\\d+")) {
            // 过长数字视为非「第几项」（避免 Integer 溢出；多为标题内时间戳）
            if (t.length() > 9) {
                return -1;
            }
            long n = Long.parseLong(t, 10);
            if (n < 1 || n > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) n - 1;
        }
        if (t.contains("第一个") || t.equals("一") || t.matches("报名\\s*1")) return 0;
        if (t.contains("第二个") || t.equals("二") || t.matches("报名\\s*2")) return 1;
        if (t.contains("第三个") || t.equals("三") || t.matches("报名\\s*3")) return 2;
        if (t.contains("第四个") || t.equals("四") || t.matches("报名\\s*4")) return 3;
        if (t.matches("报名第?[一二三四五六七八九十]个?")) {
            String num = t.replaceAll("报名第?|个", "").trim();
            if (num.equals("一")) return 0;
            if (num.equals("二")) return 1;
            if (num.equals("三")) return 2;
            if (num.equals("四")) return 3;
            if (num.equals("五")) return 4;
            if (num.matches("\\d+")) return Math.max(0, Integer.parseInt(num, 10) - 1);
        }
        return -1;
    }

    private List<String> getLastActivityIds(String sessionId, String userId) {
        return memoryService.getWorkingMemory(sessionId, userId)
                .map(m -> LastActivityIdsSupport.normalize(m.get("lastActivityIds")))
                .orElse(null);
    }

    private String extractKeyword(String text) {
        String keyword = text.replaceAll(".*搜索|.*查询|.*找|.*看看", "").trim();
        return keyword.isEmpty() ? null : keyword;
    }

    @Override
    public Map<String, List<SlotDefinition>> getSlotDefinitions() {
        return new HashMap<>(slotDefinitions);
    }

    @Override
    public void registerSlotDefinitions(String intentType, List<SlotDefinition> definitions) {
        slotDefinitions.put(intentType, definitions);
    }
}
