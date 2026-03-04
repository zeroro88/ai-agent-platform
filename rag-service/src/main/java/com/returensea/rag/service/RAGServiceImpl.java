package com.returensea.rag.service;

import com.returensea.common.model.RAGRequest;
import com.returensea.common.model.RAGResponse;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RAGServiceImpl implements RAGService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final Map<String, DocumentMetadata> documentMetadata = new ConcurrentHashMap<>();

    public RAGServiceImpl(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        initializeSampleDocuments();
    }

    private void initializeSampleDocuments() {
        List<DocumentContent> samples = List.of(
            new DocumentContent("doc-1", "海归落户北京政策", 
                "北京海归落户条件：\n1. 学历要求：海外硕士及以上学位\n2. 工作单位：需要在北京市有社保缴纳记录\n3. 年龄要求：不超过45周岁\n4. 回国时间：需要在回国后2年内办理\n\n所需材料：\n- 学历认证报告\n- 护照及签证复印件\n- 社保缴纳证明\n- 住房证明", 
                Map.of("type", "policy", "city", "北京", "category", "落户")),
            new DocumentContent("doc-2", "海归落户上海政策",
                "上海海归落户条件：\n1. 学历要求：海外本科及以上学位\n2. 社保要求：连续6个月缴纳社保\n3. 创业支持：创业可走绿色通道\n\n优惠政策：\n- 配偶子女可随迁\n- 购房优惠\n- 子女教育优先", 
                Map.of("type", "policy", "city", "上海", "category", "落户")),
            new DocumentContent("doc-3", "海归创业扶持政策",
                "各地创业扶持政策汇总：\n\n北京：\n- 创业启动资金10-50万\n- 办公场地补贴\n- 税收减免\n\n上海：\n- 创业担保贷款\n- 人才公寓\n- 研发补贴\n\n深圳：\n- 创业补贴最高50万\n- 场地租金优惠\n- 人才住房", 
                Map.of("type", "policy", "city", "全国", "category", "创业")),
            new DocumentContent("doc-4", "海归生活补贴政策",
                "各地生活补贴标准：\n\n北京：每月1000-3000元\n上海：每月1500-5000元\n深圳：每月2000-6000元\n杭州：每月1000-3000元\n\n申请条件：\n- 学历认证\n- 社保缴纳\n- 住房证明", 
                Map.of("type", "policy", "city", "全国", "category", "补贴")),
            new DocumentContent("doc-5", "海归购车优惠政策",
                "海归购车免税政策：\n\n1. 免税条件：\n- 海外学习1年以上\n- 学成后2年内入境\n- 毕业后首次入境1年内\n\n2. 免税额度：\n- 免除进口零部件关税\n- 购置税减免\n\n3. 办理流程：\n- 海关办理免税证明\n- 4S店购车", 
                Map.of("type", "policy", "city", "全国", "category", "购车"))
        );

        for (DocumentContent doc : samples) {
            indexDocument(doc.id, doc.content, doc.title, doc.metadata);
        }
        log.info("Initialized {} sample documents", samples.size());
    }

    @Override
    public RAGResponse query(RAGRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            List<RAGResponse.DocumentChunk> chunks = retrieve(request.getQuery(), request.getTopK());
            
            String answer = generateAnswer(request.getQuery(), chunks);
            
            long retrievalTime = System.currentTimeMillis() - startTime;
            
            return RAGResponse.builder()
                    .answer(answer)
                    .chunks(chunks)
                    .confidence(chunks.isEmpty() ? 0.0 : chunks.get(0).getScore())
                    .intentType(request.getIntentType())
                    .retrievalTimeMs(retrievalTime)
                    .generationTimeMs(System.currentTimeMillis() - startTime - retrievalTime)
                    .build();
        } catch (Exception e) {
            log.error("Error querying RAG: {}", e.getMessage(), e);
            return RAGResponse.builder()
                    .answer("查询知识库时出错：" + e.getMessage())
                    .confidence(0.0)
                    .retrievalTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public List<RAGResponse.DocumentChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    @Override
    public List<RAGResponse.DocumentChunk> retrieve(String query, int topK, Map<String, Object> filters) {
        try {
            log.info("Retrieving documents for query: {}, filters: {}", query, filters);
            
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK * 3)
                    .minScore(0.3)
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> allMatches = searchResult.matches();

            if (filters != null && !filters.isEmpty()) {
                allMatches = filterByMetadata(allMatches, filters);
            }
            
            return allMatches.stream()
                    .limit(topK)
                    .map(match -> {
                        TextSegment segment = match.embedded();
                        String docId = segment.metadata().getString("documentId");
                        String chunkIdStr = segment.metadata().getString("chunkId");
                        String paragraphId = chunkIdStr != null ? chunkIdStr : docId;
                        DocumentMetadata meta = documentMetadata.get(docId);
                        
                        return RAGResponse.DocumentChunk.builder()
                                .chunkId(paragraphId)
                                .documentId(docId)
                                .documentTitle(meta != null ? meta.title : "未知")
                                .content(segment.text())
                                .score(match.score())
                                .metadata(segment.metadata().toString())
                                .build();
                    })
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            log.error("Error retrieving documents: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private List<EmbeddingMatch<TextSegment>> filterByMetadata(
            List<EmbeddingMatch<TextSegment>> matches, 
            Map<String, Object> filters) {
        
        String filterCity = (String) filters.get("city");
        List<String> filterTags = filters.get("tags") instanceof List ? (List<String>) filters.get("tags") : null;
        
        return matches.stream()
                .filter(match -> {
                    TextSegment segment = match.embedded();
                    var meta = segment.metadata();
                    
                    if (filterCity != null && !filterCity.isEmpty()) {
                        String docCity = meta.getString("city");
                        if (docCity != null && !docCity.equals(filterCity) && !docCity.equals("全国")) {
                            return false;
                        }
                    }
                    
                    if (filterTags != null && !filterTags.isEmpty()) {
                        String category = meta.getString("category");
                        if (category != null && !filterTags.contains(category)) {
                            return false;
                        }
                    }
                    
                    return match.score() >= 0.6;
                })
                .collect(Collectors.toList());
    }

    private String generateAnswer(String query, List<RAGResponse.DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return "抱歉，我没有找到相关信息。建议您换个关键词或咨询人工客服。";
        }
        
        StringBuilder answer = new StringBuilder();
        
        if (chunks.size() == 1) {
            answer.append("根据检索到的信息：\n\n");
            answer.append(chunks.get(0).getContent());
        } else {
            answer.append("我找到了以下相关信息：\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                RAGResponse.DocumentChunk chunk = chunks.get(i);
                answer.append("【").append(i + 1).append("】").append(chunk.getDocumentTitle()).append("\n");
                answer.append(chunk.getContent().substring(0, Math.min(200, chunk.getContent().length())));
                if (chunk.getContent().length() > 200) {
                    answer.append("...");
                }
                answer.append("\n\n");
            }
        }
        
        answer.append("---\n📚 参考来源：");
        for (RAGResponse.DocumentChunk chunk : chunks) {
            answer.append("\n• ").append(chunk.getDocumentTitle())
                  .append(" (段落 ID: ").append(chunk.getChunkId()).append(")");
        }
        
        return answer.toString();
    }

    @Override
    public void indexDocument(String documentId, String content, String title, Map<String, Object> metadata) {
        try {
            TextSegment segment = TextSegment.from(content);
            segment.metadata().put("documentId", documentId);
            segment.metadata().put("title", title);
            segment.metadata().put("chunkId", documentId);
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                Object v = entry.getValue();
                segment.metadata().put(entry.getKey(), v == null ? "" : String.valueOf(v));
            }
            
            Embedding embedding = embeddingModel.embed(content).content();
            embeddingStore.add(embedding, segment);
            
            documentMetadata.put(documentId, new DocumentMetadata(documentId, title, metadata));
            log.info("Indexed document: {} - {}", documentId, title);
            
        } catch (Exception e) {
            log.error("Error indexing document {}: {}", documentId, e.getMessage(), e);
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        documentMetadata.remove(documentId);
        log.info("Deleted document metadata: {}", documentId);
    }

    private record DocumentContent(String id, String title, String content, Map<String, Object> metadata) {}
    
    private record DocumentMetadata(String id, String title, Map<String, Object> metadata) {}
}
