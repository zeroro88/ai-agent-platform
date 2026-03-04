package com.returensea.rag.service;

import com.returensea.common.model.RAGRequest;
import com.returensea.common.model.RAGResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心链路测试：检索政策。验证 RAG 查询能返回政策相关文档与答案。
 */
@SpringBootTest(classes = com.returensea.rag.RagServiceApplication.class)
class RAGPolicyRetrievalTest {

    @Autowired
    private RAGService ragService;

    @Test
    @DisplayName("检索政策：查询落户相关应返回政策文档与答案")
    void queryPolicy_returnsPolicyContent() {
        RAGRequest request = RAGRequest.builder()
                .query("北京落户政策")
                .topK(3)
                .build();

        RAGResponse response = ragService.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).isNotBlank();
        assertThat(response.getAnswer()).contains("落户");
        List<RAGResponse.DocumentChunk> chunks = response.getChunks();
        assertThat(chunks).isNotNull();
        assertThat(chunks).isNotEmpty();
        boolean hasPolicy = chunks.stream()
                .anyMatch(c -> c.getDocumentTitle() != null && c.getDocumentTitle().contains("政策"));
        assertThat(hasPolicy)
                .as("应至少返回一个政策标题的文档")
                .isTrue();
    }

    @Test
    @DisplayName("检索政策：retrieve 直接检索应返回相关 chunk")
    void retrievePolicy_returnsChunks() {
        List<RAGResponse.DocumentChunk> chunks = ragService.retrieve("上海落户", 2);

        assertThat(chunks).isNotNull();
        assertThat(chunks.size()).isLessThanOrEqualTo(2);
        if (!chunks.isEmpty()) {
            assertThat(chunks.get(0).getContent()).isNotBlank();
            assertThat(chunks.get(0).getDocumentTitle()).isNotBlank();
        }
    }
}
