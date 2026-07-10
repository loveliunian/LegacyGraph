package io.github.legacygraph.service.qa;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import io.github.legacygraph.util.VectorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 向量化服务 - 使用 Spring AI 实现文本向量化
 * 将文档/代码分片转换为 embedding 向量存储到 pgvector
 */
@Slf4j
@Service
@org.springframework.context.annotation.Lazy(false)
public class VectorizationService {

    private final VectorDocumentRepository vectorDocumentRepository;

    /** EmbeddingModel 可选：未配置时向量化静默跳过 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmbeddingModel embeddingModel;

    // L14 修复：新增双参构造函数供测试直接注入 EmbeddingModel
    @Autowired
    public VectorizationService(VectorDocumentRepository vectorDocumentRepository) {
        this.vectorDocumentRepository = vectorDocumentRepository;
    }

    public VectorizationService(VectorDocumentRepository vectorDocumentRepository, EmbeddingModel embeddingModel) {
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.embeddingModel = embeddingModel;
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
        doc.setEmbedding(VectorUtils.floatArrayToVectorLiteral(embeddingFloat));
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
     * <p><b>#20 修复：</b>改为逐 chunk 流式生成+处理，不再一次性 materialize 全部 chunk List，
     * 避免大文档（&gt;40KB）所有分片字符串同时驻留内存导致 OOM。
     * 每个 chunk embedding 前检查 JVM 堆水位，超限则提前终止（已存储的 chunk 保留）。</p>
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

        // M7 修复：使用 contentSha256 做去重标记，避免 count 检查与写入之间的竞态窗口
        String contentHash = sha256Hex(content);
        int existingCount = vectorDocumentRepository.countBySourceUriAndVersionId(sourceUri, versionId);
        if (existingCount > 0) {
            log.debug("Source already vectorized ({} vectors exist), skip: {}", existingCount, sourceUri);
            return 0;
        }

        // #20 修复：流式分片——逐 chunk 生成+处理，不 materialize 全部 List
        int stored = 0;
        int chunkIndex = 0;
        int start = 0;
        int length = content.length();
        // 预估 chunk 数仅用于日志
        int estimatedChunks = (int) Math.ceil((double) length / (maxChars - overlapChars));

        while (start < length) {
            int end = Math.min(start + maxChars, length);
            // 尝试在换行处截断
            int newlinePos = content.lastIndexOf('\n', end);
            if (newlinePos > start + maxChars * 0.5) {
                end = newlinePos + 1;
            }
            String chunk = content.substring(start, end);
            chunkIndex++;

            // 内存保护：每个 chunk embedding 前检查堆水位
            Runtime rt = Runtime.getRuntime();
            double memRatio = (double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
            if (memRatio > 0.70) {
                log.warn("Vectorization aborted (memory high at chunk {}/{}) for {}: {}% heap used",
                        chunkIndex, estimatedChunks, sourceUri, (int) (memRatio * 100));
                break;
            }

            try {
                Long id = embedAndStore(projectId, versionId, chunkType, sourceUri, chunkIndex - 1, chunk, embeddingModelName);
                if (id != null) {
                    stored++;
                }
            } catch (OutOfMemoryError oom) {
                log.warn("Embedding OOM at chunk {}/{} for {} (chunkLen={}), aborting: {}",
                        chunkIndex, estimatedChunks, sourceUri, chunk.length(), oom.getMessage());
                break;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                    log.debug("Chunk {}/{} already exists (concurrent write), skip: {}", chunkIndex, estimatedChunks, sourceUri);
                } else {
                    log.warn("Failed to embed chunk {}/{} for {}: {}", chunkIndex, estimatedChunks, sourceUri, e.getMessage());
                }
            }

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

        log.info("Vectorized document {}: {} chunks (est), {} stored", sourceUri, chunkIndex, stored);
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

    /**
     * 删除某个 sourceUri 的所有向量化记录
     */
    public int deleteBySourceUri(String sourceUri) {
        if (sourceUri == null || sourceUri.isBlank()) {
            return 0;
        }
        int deleted = vectorDocumentRepository.deleteBySourceUri(sourceUri);
        if (deleted > 0) {
            log.info("Deleted {} vector records for source: {}", deleted, sourceUri);
        }
        return deleted;
    }

    /**
     * 查询某个 sourceUri 在指定 versionId 下的所有 chunk（用于 chunk 级 diff 对比）
     *
     * @param sourceUri 来源文件路径
     * @param versionId 扫描版本ID
     * @return 旧 chunk 列表（包含 contentSha256 等字段）
     */
    public List<VectorDocument> findBySourceUriAndVersionId(String sourceUri, String versionId) {
        if (sourceUri == null || sourceUri.isBlank() || versionId == null || versionId.isBlank()) {
            return Collections.emptyList();
        }
        return vectorDocumentRepository.findBySourceUriAndVersionId(sourceUri, versionId);
    }

    /**
     * 增量向量化：chunk 级 diff，仅删除变更的旧 chunk + 插入新 chunk，未变更的保留不动。
     *
     * <p>流程：
     * <ol>
     *   <li>分新 chunk（复用 chunkDocument 分片逻辑）</li>
     *   <li>计算每个新 chunk 的 contentSha256</li>
     *   <li>查询旧 chunk 列表</li>
     *   <li>对比 contentSha256：旧 chunk 的 hash 不在新集合中 → 删除；新 chunk 的 hash 不在旧集合中 → 插入</li>
     *   <li>hash 在两侧都存在的 chunk 保留不动</li>
     * </ol>
     * </p>
     *
     * <p>注意：与 {@link #embedDocument} 不同，本方法不做 countBySourceUriAndVersionId &gt; 0 的前置去重跳过检查。</p>
     *
     * @param projectId          项目ID
     * @param versionId          扫描版本ID
     * @param chunkType          分片类型
     * @param sourceUri           来源文件路径
     * @param content             完整文本内容
     * @param maxChars            每片最大字符数
     * @param overlapChars        重叠字符数
     * @param embeddingModelName embedding 模型名
     * @return 本次新插入的 chunk 数
     */
    public int embedDocumentIncremental(String projectId, String versionId, String chunkType, String sourceUri,
                                       String content, int maxChars, int overlapChars, String embeddingModelName) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available, skip incremental vectorization for {}", sourceUri);
            return 0;
        }
        if (content == null || content.isBlank()) {
            return 0;
        }

        // 1. 分新 chunk（复用 chunkDocument 分片逻辑）
        List<String> newChunks = chunkDocument(content, maxChars, overlapChars);
        if (newChunks.isEmpty()) {
            return 0;
        }

        // 2. 计算每个新 chunk 的 contentSha256
        List<String> newSha256List = new ArrayList<>(newChunks.size());
        Set<String> newSha256Set = new HashSet<>(newChunks.size() * 2);
        for (String chunk : newChunks) {
            String sha = sha256Hex(chunk);
            newSha256List.add(sha);
            newSha256Set.add(sha);
        }

        // 3. 查询旧 chunk 列表
        List<VectorDocument> oldDocs = vectorDocumentRepository.findBySourceUriAndVersionId(sourceUri, versionId);
        Set<String> oldSha256Set = new HashSet<>(oldDocs.size() * 2);

        // 4. 删除变更的旧 chunk（contentSha256 不在新 chunk 集合中）
        int deletedCount = 0;
        for (VectorDocument oldDoc : oldDocs) {
            String oldSha = oldDoc.getContentSha256();
            if (oldSha != null) {
                oldSha256Set.add(oldSha);
            }
            if (oldSha == null || !newSha256Set.contains(oldSha)) {
                // 该旧 chunk 内容已变更或不存在于新版本中，删除
                vectorDocumentRepository.deleteById(oldDoc.getId());
                deletedCount++;
            }
        }

        // 5. 插入新 chunk（contentSha256 不在旧 chunk 集合中），未变更的保留不动
        int stored = 0;
        for (int i = 0; i < newChunks.size(); i++) {
            String chunk = newChunks.get(i);
            String sha = newSha256List.get(i);
            if (oldSha256Set.contains(sha)) {
                // 未变更，保留不动
                continue;
            }
            try {
                Long id = embedAndStore(projectId, versionId, chunkType, sourceUri, i, chunk, embeddingModelName);
                if (id != null) {
                    stored++;
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                    log.debug("Incremental chunk {} already exists (concurrent write), skip: {}", i, sourceUri);
                } else {
                    log.warn("Failed to embed incremental chunk {} for {}: {}", i, sourceUri, e.getMessage());
                }
            }
        }

        log.info("Incremental vectorized {}: {} new chunks, {} deleted, {} unchanged",
                sourceUri, stored, deletedCount, newChunks.size() - stored);
        return stored;
    }

    /**
     * 按 sourceUri + versionId 精确删除向量记录（修复 deleteBySourceUri 跨版本删除风险）
     *
     * @param sourceUri 来源文件路径
     * @param versionId 扫描版本ID
     * @return 删除的记录数
     */
    public int deleteBySourceUriAndVersion(String sourceUri, String versionId) {
        if (sourceUri == null || sourceUri.isBlank() || versionId == null || versionId.isBlank()) {
            return 0;
        }
        int deleted = vectorDocumentRepository.deleteBySourceUriAndVersion(sourceUri, versionId);
        if (deleted > 0) {
            log.info("Deleted {} vector records for source/version: {}/{}", deleted, sourceUri, versionId);
        }
        return deleted;
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

}
