package com.returensea.rag.service;

import com.returensea.common.model.RAGRequest;
import com.returensea.common.model.RAGResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 middleware profile 下 RAG 使用 Milvus 向量库。
 * 运行前请先启动: docker compose -f docker/docker-compose.yml up -d milvus
 */
@SpringBootTest(classes = com.returensea.rag.RagServiceApplication.class)
@ActiveProfiles("middleware")
class RAGMilvusIntegrationTest {

    @Autowired
    private RAGService ragService;

    @Test
    @DisplayName("middleware 下写入文档后可检索到")
    void indexThenRetrieve_returnsChunk() {
        String docId = "milvus-test-" + System.currentTimeMillis();
        ragService.indexDocument(
                docId,
                "集成测试专用内容：海归人才落户深圳条件与补贴申请流程。",
                "Milvus集成测试文档",
                Map.of("type", "policy", "city", "深圳", "category", "落户"));

        List<RAGResponse.DocumentChunk> chunks = ragService.retrieve("深圳落户补贴", 3);
        assertThat(chunks).isNotNull();
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().anyMatch(c ->
                c.getContent() != null && c.getContent().contains("深圳")))
                .as("应能检索到刚写入的文档")
                .isTrue();
    }

    @Test
    @DisplayName("middleware 下 query 接口返回答案与引用")
    void query_returnsAnswerAndChunks() {
        RAGResponse response = ragService.query(RAGRequest.builder()
                .query("北京落户")
                .topK(2)
                .build());
        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).isNotBlank();
        assertThat(response.getChunks()).isNotNull();
    }
}
