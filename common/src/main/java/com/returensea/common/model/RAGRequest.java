package com.returensea.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RAGRequest {
    private String query;
    private String intentType;
    private Map<String, Object> filters;
    private int topK;
    private boolean requireReferences;
    private String userId;
    private String sessionId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RAGFilter {
        private List<String> cities;
        private List<String> tags;
        private String dateFrom;
        private String dateTo;
        private List<String> documentTypes;
    }
}
