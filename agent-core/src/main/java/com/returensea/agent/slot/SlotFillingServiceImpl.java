package com.returensea.agent.slot;

import com.returensea.agent.memory.MemoryService;
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
        Pattern pattern = Pattern.compile("1[3-9]\\d{9}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
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
        String[] parts = text.split("[，。,\\.]");
        for (String part : parts) {
            if (part.contains("我叫") || part.contains("姓名") || part.contains("我叫")) {
                return part.replaceAll(".*叫", "").replace("姓名", "").trim();
            }
        }
        return null;
    }

    private String extractActivityId(String text, String sessionId, String userId) {
        if (text == null) return null;
        String trimmed = text.trim();

        Pattern pattern = Pattern.compile("act-\\d+");
        Matcher matcher = pattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }

        // 解析「1」「2」「第一个」「报名第一个」等为最近一次活动列表中的序号
        int index = parseOrdinalSelection(trimmed);
        if (index >= 0 && sessionId != null && userId != null) {
            List<String> lastIds = getLastActivityIds(sessionId, userId);
            if (lastIds != null && index < lastIds.size()) {
                String resolved = lastIds.get(index);
                log.debug("Resolved selection '{}' -> activityId {}", trimmed, resolved);
                return resolved;
            }
        }
        return null;
    }

    /** 解析用户输入的序号：1、2、第一个、报名第一个、报名1 等，返回 0-based 下标，无法解析返回 -1 */
    private int parseOrdinalSelection(String text) {
        if (text == null || text.isEmpty()) return -1;
        String t = text.trim();
        if (t.matches("\\d+")) {
            int n = Integer.parseInt(t, 10);
            return n >= 1 ? n - 1 : -1;
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

    @SuppressWarnings("unchecked")
    private List<String> getLastActivityIds(String sessionId, String userId) {
        return memoryService.getWorkingMemory(sessionId, userId)
                .map(m -> m.get("lastActivityIds"))
                .filter(List.class::isInstance)
                .map(list -> (List<String>) list)
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
