package io.github.legacygraph.repository;

import io.github.legacygraph.entity.Fact;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FactRepository extends LegacyBaseMapper<Fact> {

    /**
     * 原子 upsert：冲突时更新可变的业务字段（避免 check-then-insert 竞态）。
     *
     * @return 受影响行数（INSERT 或 UPDATE 都返回非零）
     */
    @Insert("INSERT INTO lg_fact (id, project_id, version_id, fact_type, fact_key, " +
            "fact_name, source_type, source_path, start_line, end_line, source_line, " +
            "content_summary, normalized_data, confidence, status, mapped_to_graph, " +
            "related_node_count, created_by, created_at, updated_at) " +
            "VALUES (#{id}, #{projectId}, #{versionId}, #{factType}, #{factKey}, " +
            "#{factName}, #{sourceType}, #{sourcePath}, #{startLine}, #{endLine}, #{sourceLine}, " +
            "#{contentSummary}, #{normalizedData}, #{confidence}, #{status}, #{mappedToGraph}, " +
            "#{relatedNodeCount}, #{createdBy}, #{createdAt}, #{updatedAt}) " +
            "ON CONFLICT (project_id, version_id, fact_type, fact_key) DO UPDATE SET " +
            "fact_name = EXCLUDED.fact_name, " +
            "source_type = EXCLUDED.source_type, " +
            "source_path = EXCLUDED.source_path, " +
            "start_line = EXCLUDED.start_line, " +
            "end_line = EXCLUDED.end_line, " +
            "source_line = EXCLUDED.source_line, " +
            "content_summary = EXCLUDED.content_summary, " +
            "normalized_data = EXCLUDED.normalized_data, " +
            "confidence = EXCLUDED.confidence, " +
            "status = EXCLUDED.status, " +
            "mapped_to_graph = EXCLUDED.mapped_to_graph, " +
            "related_node_count = EXCLUDED.related_node_count, " +
            "created_by = EXCLUDED.created_by, " +
            "updated_at = EXCLUDED.updated_at")
    int upsert(Fact fact);
}
