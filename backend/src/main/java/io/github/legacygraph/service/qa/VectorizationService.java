package io.github.legacygraph.service.qa;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量化服务 - 使用 Spring AI 实现文本向量化
 * 将文档/代码分片转换为 embedding 向量存储到 pgvector
 */
@Slf4j
@Service
public class VectorizationService {

    private final VectorDocumentRepository vectorDocumentRepository;

    /** EmbeddingModel 可选：未配置时向量化静默跳过 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmbeddingModel embeddingModel;

    public VectorizationService(VectorDocumentRepository vectorDocumentRepository) {
        this.vectorDocumentRepository = vectorDocumentRepository;
    }

    /**
     * 检查 EmbeddingModel 是否可用
     */
    public boolean isAvailable() {
        return embeddingModel != null;
    }

    /**
     * 对单个文本分片进行向量化并存储
     *
     * @param projectId          项目ID（UUID字符串）
     * @param versionId          扫描版本ID
     * @param chunkType          分片类型：DOC/CODE/DB/UI
     * @param sourceUri           来源文件路径
     * @param chunkIndex          分片索引
     * @param content             分片文本内容
     * @param embeddingModelName 使用的 embedding 模型名
     * @return 存储后的记录 ID，EmbeddingModel 不可用时返回 null
     */
    @Transactional
    public Long embedAndStore(String projectId, String versionId, String chunkType, String sourceUri,
                              int chunkIndex, String content, String embeddingModelName) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available, skip vectorization for chunk {}", chunkIndex);
            return null;
        }
        if (content == null || content.isBlank()) {
            return null;
        }
        log.debug("Vectorizing: projectId={}, chunkIndex={}, length={}", projectId, chunkIndex, content.length());

        // 计算 hash 用于去重
        String contentSha256 = sha256Hex(content);
        String sourceHash = sha256Hex(sourceUri + chunkIndex);

        // 执行向量化
        float[] embeddingFloat = embeddingModel.embed(content);

        VectorDocument doc = new VectorDocument();
        doc.setProjectId(projectId);
        doc.setVersionId(versionId);
        doc.setChunkType(chunkType);
        doc.setSourceUri(sourceUri);
        doc.setSourceHash(sourceHash);
        doc.setChunkIndex(chunkIndex);
        doc.setContent(content);
        doc.setContentSha256(contentSha256);
        doc.setMeta("{}");
        doc.setEmbedding(floatArrayToVectorLiteral(embeddingFloat));
        doc.setEmbeddingModel(embeddingModelName);
        doc.setEmbeddingDim(embeddingFloat.length);
        doc.setCreatedAt(LocalDateTime.now());

        vectorDocumentRepository.insert(doc);

        log.debug("Vectorized and stored chunk: id={}, projectId={}, chunkIndex={}", doc.getId(), projectId, chunkIndex);
        return doc.getId();
    }

    /**
     * 对文本进行分片并批量向量化存储
     *
     * @param projectId          项目ID
     * @param versionId          扫描版本ID
     * @param chunkType          分片类型
     * @param sourceUri           来源文件路径
     * @param content             完整文本内容
     * @param maxChars            每片最大字符数
     * @param overlapChars        重叠字符数
     * @param embeddingModelName embedding 模型名
     * @return 成功存储的分片数
     */
    public int embedDocument(String projectId, String versionId, String chunkType, String sourceUri,
                             String content, int maxChars, int overlapChars, String embeddingModelName) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available, skip vectorization for {}", sourceUri);
            return 0;
        }
        if (content == null || content.isBlank()) {
            return 0;
        }

        List<String> chunks = chunkDocument(content, maxChars, overlapChars);
        int stored = 0;
        for (int i = 0; i < chunks.size(); i++) {
            try {
                Long id = embedAndStore(projectId, versionId, chunkType, sourceUri, i, chunks.get(i), embeddingModelName);
                if (id != null) {
                    stored++;
                }
            } catch (Exception e) {
                log.warn("Failed to embed chunk {}/{} for {}: {}", i, chunks.size(), sourceUri, e.getMessage());
            }
        }
        log.info("Vectorized document {}: {} chunks, {} stored", sourceUri, chunks.size(), stored);
        return stored;
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
            // 计算下一段起点，保证严格前进
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
     */
    public boolean isProbablyDuplicate(List<Double> embedding1, List<Double> embedding2) {
        double similarity = cosineSimilarity(embedding1, embedding2);
        return similarity >= 0.92;
    }

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

    private List<Double> floatArrayToDoubleList(float[] floats) {
        List<Double> result = new ArrayList<>(floats.length);
        for (float f : floats) {
            result.add((double) f);
        }
        return result;
    }

    private String floatArrayToVectorLiteral(float[] floats) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < floats.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(Float.toString(floats[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
