package com.returensea.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RAGResponse {
    private String answer;
    private List<DocumentChunk> chunks;
    private double confidence;
    private String intentType;
    private long retrievalTimeMs;
    private long generationTimeMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentChunk {
        /** 段落 ID，与 chunkId 一致，用于引用溯源；前端/策略统一使用此字段定位来源段落。 */
        private String chunkId;
        private String documentId;
        private String documentTitle;
        private String content;
        private double score;
        private String metadata;
    }
}
