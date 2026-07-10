package io.github.legacygraph.repository;

import io.github.legacygraph.entity.NodeEvidence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NodeEvidenceRepository extends LegacyBaseMapper<NodeEvidence> {

    /**
     * 按 (node_id, evidence_id) 原子 upsert：冲突时忽略（不重复插入）。
     * <p>
     * 依赖 lg_node_evidence 表的 UNIQUE(node_id, evidence_id) 约束。
     * 用于 EvidenceGraphWriter.createEvidenceForNode() 在已存在节点上重复创建证据时
     * 避免抛出 DuplicateKeyException。
     *
     * @param nodeEvidence 节点证据关联实体
     * @return 受影响行数：INSERT 成功返回 1，冲突忽略返回 0
     */
    @Insert("INSERT INTO lg_node_evidence (id, node_id, evidence_id, relation_type, created_at) "
            + "VALUES (#{id}, #{nodeId}, #{evidenceId}, #{relationType}, #{createdAt}) "
            + "ON CONFLICT (node_id, evidence_id) DO NOTHING")
    int insertOrIgnore(NodeEvidence nodeEvidence);

    /**
     * 统计指定版本下有证据关联的去重节点数。
     * <p>lg_node_evidence 无 projectId/versionId 字段，通过 JOIN lg_evidence
     * 按证据的 project_id/version_id 过滤。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @return 有证据关联的去重节点数
     */
    @Select("SELECT COUNT(DISTINCT ne.node_id) FROM lg_node_evidence ne "
            + "JOIN lg_evidence e ON ne.evidence_id = e.id "
            + "WHERE e.project_id = #{projectId} AND e.version_id = #{versionId} "
            + "AND (ne.deleted IS NULL OR ne.deleted = 0) "
            + "AND (e.deleted IS NULL OR e.deleted = 0)")
    long countDistinctNodeIds(@Param("projectId") String projectId,
                              @Param("versionId") String versionId);
}
