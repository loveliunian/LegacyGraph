package io.github.legacygraph.service;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量化服务 - 处理代码、文档、SQL 等片段的向量化
 *
 * 依照详细设计文档：
 * - 在线召回：使用小向量（512/768 维）text-embedding-3-small
 * - 离线高质量：使用大向量 text-embedding-3-large
 */
@Slf4j
@Service
public class VectorizationService {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorDocumentRepository vectorDocumentRepository;

    /**
     * 对单个文档分片进行向量化并存储
     */
    public Long embedAndStore(Long projectId, String chunkType, String sourceUri,
                              int chunkIndex, String content, String embeddingModelName, int dim) {

        // 计算 hash 用于去重
        String contentSha256 = sha256Hex(content);
        String sourceHash = sha256Hex(sourceUri + chunkIndex);

        // 执行向量化
        float[] embeddingFloat = embeddingModel.embed(content);
        List<Double> embedding = floatArrayToDoubleList(embeddingFloat);

        // 截断/调整维度（如果需要）
        if (embedding.size() > dim) {
            embedding = embedding.subList(0, dim);
        }

        // Spring AI pgvector 会处理存储，这里我们保存元数据
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
        doc.setEmbeddingDim(dim);

        vectorDocumentRepository.insert(doc);

        log.debug("Embedded and stored chunk: projectId={}, sourceUri={}, chunkIndex={}",
                projectId, sourceUri, chunkIndex);

        return doc.getId();
    }

    /**
     * 对文本进行分片（按语义边界）
     */
    public List<String> chunkDocument(String content, int maxTokens, int overlapTokens) {
        // 简化实现：按段落分割，然后合并到接近 maxTokens
        // 生产环境可以使用更智能的分片器

        List<String> paragraphs = splitIntoParagraphs(content);
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String para : paragraphs) {
            int paraTokens = estimateTokens(para);
            if (currentTokens + paraTokens > maxTokens && currentTokens > 0) {
                chunks.add(currentChunk.toString().trim());
                // 保留重叠
                currentChunk = new StringBuilder(getOverlap(currentChunk.toString(), overlapTokens));
                currentTokens = estimateTokens(currentChunk.toString());
            }
            if (!para.isBlank()) {
                currentChunk.append("\n").append(para);
                currentTokens += paraTokens;
            }
        }
        if (currentTokens > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitIntoParagraphs(String content) {
        String[] split = content.split("\\n\\s*\\n");
        List<String> result = new ArrayList<>();
        for (String s : split) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private int estimateTokens(String text) {
        // 简单估算：~4 字符 = 1 token
        return (int) Math.ceil(text.length() / 4.0);
    }

    private String getOverlap(String text, int overlapTokens) {
        String[] words = text.split("\\s+");
        int overlapWords = overlapTokens * 2; // 近似转换
        if (words.length <= overlapWords) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = words.length - overlapWords; i < words.length; i++) {
            sb.append(words[i]).append(" ");
        }
        return sb.toString();
    }

    /**
     * 近似去重检测 - 同类型 chunk cosine > 0.995 且字数差异 < 3% 则重复
     */
    public boolean isProbablyDuplicate(List<Double> embedding1, List<Double> embedding2,
                                       int len1, int len2) {
        double cosine = cosineSimilarity(embedding1, embedding2);
        double lenDiff = Math.abs(len1 - len2) / (double) Math.max(len1, len2);
        return cosine > 0.995 && lenDiff < 0.03;
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0.0;
        double magA = 0.0;
        double magB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double ai = a.get(i);
            double bi = b.get(i);
            dot += ai * bi;
            magA += ai * ai;
            magB += bi * bi;
        }
        return dot / (Math.sqrt(magA) * Math.sqrt(magB));
    }

    /**
     * SHA-256 hash function
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
