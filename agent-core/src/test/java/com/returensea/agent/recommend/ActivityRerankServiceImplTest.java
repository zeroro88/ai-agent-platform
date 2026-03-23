package com.returensea.agent.recommend;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityRerankServiceImplTest {

    @Mock
    ChatModel chatLanguageModel;

    @InjectMocks
    ActivityRerankServiceImpl rerankService;

    @Test
    void mergeAndSort_putsHighestNumericIdFirst_whenLlmOutputsOldActivitiesFirst() {
        when(chatLanguageModel.chat(anyString())).thenReturn("1|旧活动\n5|论坛");
        List<ActivityRerankService.ActivityCandidate> cands = List.of(
                new ActivityRerankService.ActivityCandidate("5", "海归职业发展论坛", "上海", "d"),
                new ActivityRerankService.ActivityCandidate("21", "微医测试展", "上海", "用户发起活动"),
                new ActivityRerankService.ActivityCandidate("1", "创业分享会", "上海", "d")
        );
        List<ActivityRerankService.RerankedActivity> out = rerankService.rerankWithReasons(cands, "上海", 10);
        assertThat(out).extracting(ActivityRerankService.RerankedActivity::id).containsExactly("21", "5", "1");
    }

    @Test
    void fallback_keepsNewestFirstOrder_whenLlmReturnsNothing() {
        when(chatLanguageModel.chat(anyString())).thenReturn("not a valid line\n\n");
        List<ActivityRerankService.ActivityCandidate> cands = List.of(
                new ActivityRerankService.ActivityCandidate("3", "c", "上海", "d"),
                new ActivityRerankService.ActivityCandidate("10", "b", "上海", "d"),
                new ActivityRerankService.ActivityCandidate("2", "a", "上海", "d")
        );
        List<ActivityRerankService.RerankedActivity> out = rerankService.rerankWithReasons(cands, "上海", 2);
        assertThat(out).extracting(ActivityRerankService.RerankedActivity::id).containsExactly("10", "3");
    }
}
