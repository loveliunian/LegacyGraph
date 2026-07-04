package io.github.legacygraph.repository;

import io.github.legacygraph.entity.VectorDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Mapper
public interface VectorDocumentRepository extends LegacyBaseMapper<VectorDocument> {

    Logger LOG = LoggerFactory.getLogger(VectorDocumentRepository.class);

    @Select("SELECT * FROM lg_vector_document WHERE project_id = #{projectId} AND version_id = #{versionId} AND chunk_type = #{chunkType}")
    List<VectorDocument> findByProjectAndVersionAndType(String projectId, String versionId, String chunkType);

    /**
     * 根据 sourceUri 查找已向量化的文档
     */
    @Select("SELECT * FROM lg_vector_document WHERE source_uri = #{sourceUri} LIMIT 1")
    VectorDocument findBySourceUri(String sourceUri);

    /**
     * 统计某个 sourceUri 的向量化记录数
     */
    @Select("SELECT COUNT(*) FROM lg_vector_document WHERE source_uri = #{sourceUri}")
    int countBySourceUri(String sourceUri);

    /**
     * 统计某个扫描版本内 sourceUri 的向量化记录数
     */
    @Select("SELECT COUNT(*) FROM lg_vector_document WHERE source_uri = #{sourceUri} AND version_id = #{versionId}")
    int countBySourceUriAndVersionId(@Param("sourceUri") String sourceUri, @Param("versionId") String versionId);

    /**
     * 删除某个 sourceUri 的所有向量化记录
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM lg_vector_document WHERE source_uri = #{sourceUri}")
    int deleteBySourceUri(String sourceUri);

    /**
     * 使用 pgvector 余弦相似度查找相似文档（带类型过滤）
     * 注意：embedding 列是 vector 类型，由 Spring AI pgvector 自动处理
     * 这里使用原生 SQL 执行余弦相似度查询，distance 越小越相似
     */
    @Select("SELECT *, embedding <=> CAST(#{embedding} AS vector) AS distance " +
            "FROM lg_vector_document " +
            "WHERE project_id = #{projectId} " +
            "AND version_id = #{versionId} " +
            "AND chunk_type = #{chunkType} " +
            "ORDER BY distance ASC " +
            "LIMIT #{topK}")
    List<VectorDocument> findSimilarByEmbeddingWithType(
            @Param("projectId") String projectId,
            @Param("versionId") String versionId,
            @Param("embedding") String embedding,
            @Param("topK") int topK,
            @Param("chunkType") String chunkType);

    /**
     * 使用 pgvector 余弦相似度查找相似文档（不过滤类型）
     */
    @Select("SELECT *, embedding <=> CAST(#{embedding} AS vector) AS distance " +
            "FROM lg_vector_document " +
            "WHERE project_id = #{projectId} " +
            "AND version_id = #{versionId} " +
            "ORDER BY distance ASC " +
            "LIMIT #{topK}")
    List<VectorDocument> findSimilarByEmbeddingWithoutType(
            @Param("projectId") String projectId,
            @Param("versionId") String versionId,
            @Param("embedding") String embedding,
            @Param("topK") int topK);

    /**
     * 通用方法：根据是否有chunkType选择不同查询。
     * pgvector 扩展未安装或 embedding 列不存在时，记录告警并返回空列表。
     */
    default List<VectorDocument> findSimilar(String projectId, String versionId, List<Double> embedding, int topK, String chunkType) {
        // 将List<Double>转换为PostgreSQL vector格式: [x,y,z]
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding.get(i));
        }
        sb.append("]");
        String embeddingStr = sb.toString();

        try {
            if (chunkType != null && !chunkType.isBlank()) {
                return findSimilarByEmbeddingWithType(projectId, versionId, embeddingStr, topK, chunkType);
            } else {
                return findSimilarByEmbeddingWithoutType(projectId, versionId, embeddingStr, topK);
            }
        } catch (Exception e) {
            LOG.warn("pgvector similarity query failed for projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }
}
