package io.github.legacygraph.repository;

import io.github.legacygraph.entity.Evidence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EvidenceRepository extends LegacyBaseMapper<Evidence> {

    /**
     * 按 contentHash 原子 upsert：冲突时忽略（不重复插入）。
     * <p>
     * 依赖 V10 迁移创建的 partial unique index：
     * <code>CREATE UNIQUE INDEX ON lg_evidence(content_hash) WHERE content_hash IS NOT NULL AND deleted = 0</code>
     * <p>
     * ON CONFLICT WHERE 子句必须包含索引的全部谓词，否则 PostgreSQL 会报：
     * "there is no unique or exclusion constraint matching the ON CONFLICT specification"
     * </p>
     *
     * @param evidence 证据实体（contentHash 非空时才走冲突检测）
     * @return 受影响行数：INSERT 成功返回 1，冲突忽略返回 0
     */
    @Insert("INSERT INTO lg_evidence (id, project_id, version_id, evidence_type, source_path, "
            + "source_name, start_line, end_line, content_hash, content_excerpt, summary, content, "
            + "metadata, ast_path, sql_hash, chunk_id, related_node_ids, privacy_level, "
            + "redaction_policy, created_at) "
            + "VALUES (#{id}, #{projectId}, #{versionId}, #{evidenceType}, #{sourcePath}, "
            + "#{sourceName}, #{startLine}, #{endLine}, #{contentHash}, #{contentExcerpt}, #{summary}, #{content}, "
            + "#{metadata}, #{astPath}, #{sqlHash}, #{chunkId}, #{relatedNodeIds}, #{privacyLevel}, "
            + "#{redactionPolicy}, #{createdAt}) "
            + "ON CONFLICT (content_hash) WHERE content_hash IS NOT NULL AND deleted = 0 DO NOTHING")
    int insertOrIgnore(Evidence evidence);

    /**
     * 按 contentHash 查找已有证据（用于去重冲突时获取已存在的记录 ID）。
     */
    @Select("SELECT * FROM lg_evidence WHERE content_hash = #{contentHash} AND deleted = 0 LIMIT 1")
    Evidence findByContentHash(String contentHash);
}
