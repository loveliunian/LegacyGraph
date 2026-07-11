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
     * 查找项目最新的 version_id（按 created_at 降序）
     */
    @Select("SELECT version_id FROM lg_vector_document WHERE project_id = #{projectId} ORDER BY created_at DESC LIMIT 1")
    String findLatestVersionId(String projectId);

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
     * 查询某个 sourceUri 在指定 versionId 下的所有 chunk（用于 chunk 级 diff 对比）
     */
    @Select("SELECT * FROM lg_vector_document WHERE source_uri = #{sourceUri} AND version_id = #{versionId}")
    List<VectorDocument> findBySourceUriAndVersionId(@Param("sourceUri") String sourceUri, @Param("versionId") String versionId);

    /**
     * 删除某个 sourceUri 的所有向量化记录
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM lg_vector_document WHERE source_uri = #{sourceUri}")
    int deleteBySourceUri(String sourceUri);

    /**
     * 按 sourceUri + versionId 精确删除向量记录（修复 deleteBySourceUri 跨版本删除风险）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM lg_vector_document WHERE source_uri = #{sourceUri} AND version_id = #{versionId}")
    int deleteBySourceUriAndVersion(@Param("sourceUri") String sourceUri, @Param("versionId") String versionId);

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
     * 带 GraphRelease + ACL 过滤的相似度查询（按类型过滤）。
     * <p>Task 8.3：graphReleaseId 非空时按发布版本过滤；aclPrincipal 非空时
     * 仅返回无 ACL 限制或包含该主体的文档。</p>
     */
    @Select("<script>" +
            "SELECT *, embedding &lt;=&gt; CAST(#{embedding} AS vector) AS distance " +
            "FROM lg_vector_document " +
            "WHERE project_id = #{projectId} " +
            "AND version_id = #{versionId} " +
            "AND chunk_type = #{chunkType} " +
            "<if test='graphReleaseId != null and graphReleaseId != \"\"'>" +
            "AND graph_release_id = #{graphReleaseId} " +
            "</if>" +
            "<if test='aclPrincipal != null and aclPrincipal != \"\"'>" +
            "AND (acl_principals IS NULL OR acl_principals LIKE CONCAT('%', #{aclPrincipal}, '%')) " +
            "</if>" +
            "ORDER BY distance ASC " +
            "LIMIT #{topK}" +
            "</script>")
    List<VectorDocument> findSimilarByEmbeddingWithTypeFiltered(
            @Param("projectId") String projectId,
            @Param("versionId") String versionId,
            @Param("embedding") String embedding,
            @Param("topK") int topK,
            @Param("chunkType") String chunkType,
            @Param("graphReleaseId") String graphReleaseId,
            @Param("aclPrincipal") String aclPrincipal);

    /**
     * 带 GraphRelease + ACL 过滤的相似度查询（不过滤类型）。
     */
    @Select("<script>" +
            "SELECT *, embedding &lt;=&gt; CAST(#{embedding} AS vector) AS distance " +
            "FROM lg_vector_document " +
            "WHERE project_id = #{projectId} " +
            "AND version_id = #{versionId} " +
            "<if test='graphReleaseId != null and graphReleaseId != \"\"'>" +
            "AND graph_release_id = #{graphReleaseId} " +
            "</if>" +
            "<if test='aclPrincipal != null and aclPrincipal != \"\"'>" +
            "AND (acl_principals IS NULL OR acl_principals LIKE CONCAT('%', #{aclPrincipal}, '%')) " +
            "</if>" +
            "ORDER BY distance ASC " +
            "LIMIT #{topK}" +
            "</script>")
    List<VectorDocument> findSimilarByEmbeddingWithoutTypeFiltered(
            @Param("projectId") String projectId,
            @Param("versionId") String versionId,
            @Param("embedding") String embedding,
            @Param("topK") int topK,
            @Param("graphReleaseId") String graphReleaseId,
            @Param("aclPrincipal") String aclPrincipal);

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
                    projectId, versionId, e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 带 GraphRelease + ACL 过滤的相似度查询（Task 8.3）。
     * <p>当 feature flag 关闭时应调用原 {@link #findSimilar} 而非本方法。</p>
     *
     * @param graphReleaseId 图谱发布ID，null/空 时忽略该过滤
     * @param aclPrincipal   ACL 主体，null/空 时忽略该过滤
     */
    default List<VectorDocument> findSimilarWithFilters(String projectId, String versionId,
                                                        List<Double> embedding, int topK, String chunkType,
                                                        String graphReleaseId, String aclPrincipal) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding.get(i));
        }
        sb.append("]");
        String embeddingStr = sb.toString();

        try {
            if (chunkType != null && !chunkType.isBlank()) {
                return findSimilarByEmbeddingWithTypeFiltered(
                    projectId, versionId, embeddingStr, topK, chunkType, graphReleaseId, aclPrincipal);
            } else {
                return findSimilarByEmbeddingWithoutTypeFiltered(
                    projectId, versionId, embeddingStr, topK, graphReleaseId, aclPrincipal);
            }
        } catch (Exception e) {
            LOG.warn("pgvector filtered similarity query failed for projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }
}
