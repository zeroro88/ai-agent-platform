package com.returensea.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** All-MiniLM-L6-v2 向量维度为 384。 */
@Slf4j
@Configuration
public class EmbeddingStoreConfig {

    private static final int EMBEDDING_DIMENSION = 384;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${ai.rag.store.provider:in-memory}") String provider,
            @Value("${ai.rag.milvus.host:localhost}") String milvusHost,
            @Value("${ai.rag.milvus.port:19530}") int milvusPort) {
        if ("milvus".equalsIgnoreCase(provider)) {
            log.info("Using Milvus embedding store: {}:{}", milvusHost, milvusPort);
            return MilvusEmbeddingStore.builder()
                    .host(milvusHost)
                    .port(milvusPort)
                    .collectionName("rag_docs")
                    .dimension(EMBEDDING_DIMENSION)
                    .indexType(IndexType.FLAT)
                    .metricType(MetricType.COSINE)
                    .build();
        }
        log.info("Using in-memory embedding store");
        return new InMemoryEmbeddingStore<>();
    }
}
