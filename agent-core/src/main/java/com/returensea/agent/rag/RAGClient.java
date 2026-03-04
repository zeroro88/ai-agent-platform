package com.returensea.agent.rag;

import com.returensea.common.model.RAGRequest;
import com.returensea.common.model.RAGResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RAGClient {

    private final WebClient webClient;

    public RAGClient(@Value("${rag-service.base-url:http://localhost:8082}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public Mono<RAGResponse> query(String query, String intentType) {
        RAGRequest request = RAGRequest.builder()
                .query(query)
                .intentType(intentType)
                .topK(3)
                .requireReferences(true)
                .build();

        return webClient.post()
                .uri("/api/rag/query")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RAGResponse.class)
                .timeout(java.time.Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.error("Error calling RAG service: {}", e.getMessage());
                    return Mono.just(RAGResponse.builder()
                            .answer("知识库服务暂时不可用")
                            .confidence(0.0)
                            .build());
                });
    }

    public RAGResponse querySync(String query, String intentType) {
        return query(query, intentType).block();
    }
}
