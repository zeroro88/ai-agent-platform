package com.returensea.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returensea.common.model.RecommendedActivity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 与 OrchestratorImpl.buildContentJson 的 JSON 形状对齐：text 供记忆抽取，activities 供前端卡片。
 */
class ContentJsonRecommendedActivitiesContractTest {

    @Test
    void contentJsonWithActivitiesPreservesTextForSessionMemoryExtraction() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<RecommendedActivity> acts = List.of(
                RecommendedActivity.builder().id("42").title("沙龙").city("上海").ordinal(1).build());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "hello");
        m.put("type", "message");
        m.put("activities", acts);
        String json = mapper.writeValueAsString(m);

        Map<String, Object> map = mapper.readValue(json, new TypeReference<>() {});
        assertEquals("hello", map.get("text").toString());
        assertNotNull(map.get("activities"));
        List<?> rawList = (List<?>) map.get("activities");
        assertEquals(1, rawList.size());
        RecommendedActivity roundtrip = mapper.convertValue(rawList.get(0), RecommendedActivity.class);
        assertEquals("42", roundtrip.getId());
        assertEquals("沙龙", roundtrip.getTitle());
    }
}
