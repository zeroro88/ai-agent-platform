package com.returensea.rag.controller;

import com.returensea.common.model.RAGRequest;
import com.returensea.common.model.RAGResponse;
import com.returensea.rag.service.RAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RAGController {

    private final RAGService ragService;

    @GetMapping("/search")
    public List<String> search(@RequestParam String query) {
        List<RAGResponse.DocumentChunk> chunks = ragService.retrieve(query, 3);
        return chunks.stream()
                .map(chunk -> String.format("[%s] %s (相似度: %.2f)",
                        chunk.getDocumentTitle(),
                        chunk.getContent(),
                        chunk.getScore()))
                .toList();
    }

    @PostMapping("/query")
    public RAGResponse query(@RequestBody RAGRequest request) {
        return ragService.query(request);
    }
}
