package io.github.legacygraph.repository;

import io.github.legacygraph.entity.EdgeEvidence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EdgeEvidenceRepository extends LegacyBaseMapper<EdgeEvidence> {

    /** 按 (edge_id, evidence_id) 幂等关联，供已有边补证据时安全重放。 */
    @Insert("INSERT INTO lg_edge_evidence (id, edge_id, evidence_id, relation_type, created_at) "
            + "VALUES (#{id}, #{edgeId}, #{evidenceId}, #{relationType}, #{createdAt}) "
            + "ON CONFLICT (edge_id, evidence_id) DO NOTHING")
    int insertOrIgnore(EdgeEvidence edgeEvidence);

    /**
     * 统计指定版本下有证据关联的去重边数。
     * <p>lg_edge_evidence 无 projectId/versionId 字段，通过 JOIN lg_evidence
     * 按证据的 project_id/version_id 过滤。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @return 有证据关联的去重边数
     */
    @Select("SELECT COUNT(DISTINCT ee.edge_id) FROM lg_edge_evidence ee "
            + "JOIN lg_evidence e ON ee.evidence_id = e.id "
            + "WHERE e.project_id = #{projectId} AND e.version_id = #{versionId} "
            + "AND (ee.deleted IS NULL OR ee.deleted = 0) "
            + "AND (e.deleted IS NULL OR e.deleted = 0)")
    long countDistinctEdgeIds(@Param("projectId") String projectId,
                              @Param("versionId") String versionId);
}
