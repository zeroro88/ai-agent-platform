package com.returensea.rag.service;

import com.returensea.common.model.RAGRequest;
import com.returensea.common.model.RAGResponse;

import java.util.List;
import java.util.Map;

public interface RAGService {
    RAGResponse query(RAGRequest request);

    List<RAGResponse.DocumentChunk> retrieve(String query, int topK);

    List<RAGResponse.DocumentChunk> retrieve(String query, int topK, Map<String, Object> filters);

    void indexDocument(String documentId, String content, String title, Map<String, Object> metadata);

    void deleteDocument(String documentId);
}
