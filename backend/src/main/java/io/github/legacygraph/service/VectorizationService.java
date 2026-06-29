package io.github.legacygraph.service;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量化服务 - 使用 Spring AI + OpenAI 实现文本向量化
 * 将文档分片转换为 embedding 向量存储到 pgvector
 */
@Slf4j
@Service
public class VectorizationService {

    private final EmbeddingModel embeddingModel;
    private final VectorDocumentRepository vectorDocumentRepository;

    public VectorizationService(EmbeddingModel embeddingModel,
                               VectorDocumentRepository vectorDocumentRepository) {
        this.embeddingModel = embeddingModel;
        this.vectorDocumentRepository = vectorDocumentRepository;
    }

    /**
     * 对单个文档分片进行向量化并存储
     * @return 存储后的记录 ID
     */
    @Transactional
    public Long embedAndStore(Long projectId, String versionId, String chunkType, String sourceUri,
                              int chunkIndex, String content, String embeddingModelName) {
        log.info("Vectorizing: projectId={}, chunkIndex={}, length={}", projectId, chunkIndex, content.length());

        // 计算 hash 用于去重
        String contentSha256 = sha256Hex(content);
        String sourceHash = sha256Hex(sourceUri + chunkIndex);

        // 执行向量化 - Spring AI 1.0+ API
        float[] embeddingFloat = embeddingModel.embed(content);
        List<Double> embedding = floatArrayToDoubleList(embeddingFloat);

        VectorDocument doc = new VectorDocument();
        doc.setProjectId(projectId);
        doc.setChunkType(chunkType);
        doc.setSourceUri(sourceUri);
        doc.setSourceHash(sourceHash);
        doc.setChunkIndex(chunkIndex);
        doc.setContent(content);
        doc.setContentSha256(contentSha256);
        doc.setMeta("{}");
        doc.setEmbeddingModel(embeddingModelName);
        doc.setEmbeddingDim(embedding.size());
        doc.setCreatedAt(java.time.LocalDateTime.now());

        vectorDocumentRepository.insert(doc);

        log.info("Vectorized and stored chunk: id={}, projectId={}, chunkIndex={}", doc.getId(), projectId, chunkIndex);
        return doc.getId();
    }

    /**
     * 对文本进行简单分片
     * 按字符数近似分片，保留 overlap 避免切分语义
     */
    public List<String> chunkDocument(String content, int maxChars, int overlapChars) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> chunks = new ArrayList<>();
        int length = content.length();
        if (length <= maxChars) {
            chunks.add(content);
            return chunks;
        }

        int start = 0;
        while (start < length) {
            int end = Math.min(start + maxChars, length);
            // 尝试在换行处截断
            int newlinePos = content.lastIndexOf('\n', end);
            if (newlinePos > start + maxChars * 0.5) {
                end = newlinePos + 1;
            }
            chunks.add(content.substring(start, end));
            // 已到末尾，结束
            if (end >= length) {
                break;
            }
            // 计算下一段起点，保证严格前进，避免末段 chunk 短于 overlap 时原地死循环
            int nextStart = end - overlapChars;
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }

        log.debug("Chunked document into {} chunks (maxChars={}, overlap={})", chunks.size(), maxChars, overlapChars);
        return chunks;
    }

    /**
     * 计算两个向量的余弦相似度，用于去重检测
     * @return 相似度 >= 0.9 表示高度相似可能重复
     */
    public double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) {
            return 0.0;
        }

        double dot = 0.0;
        double denomA = 0.0;
        double denomB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            double ai = a.get(i);
            double bi = b.get(i);
            dot += ai * bi;
            denomA += ai * ai;
            denomB += bi * bi;
        }

        if (denomA == 0 || denomB == 0) {
            return 0.0;
        }

        return dot / (Math.sqrt(denomA) * Math.sqrt(denomB));
    }

    /**
     * 近似去重检测
     * @return true 如果相似度超过阈值，认为可能重复
     */
    public boolean isProbablyDuplicate(List<Double> embedding1, List<Double> embedding2) {
        double similarity = cosineSimilarity(embedding1, embedding2);
        return similarity >= 0.92;
    }

    /**
     * SHA-256 hash
     */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Convert float array to List<Double>
     */
    private List<Double> floatArrayToDoubleList(float[] floats) {
        List<Double> result = new ArrayList<>(floats.length);
        for (float f : floats) {
            result.add((double) f);
        }
        return result;
    }
}
