package io.github.legacygraph.repository;

import io.github.legacygraph.entity.GraphNode;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @deprecated 图数据已迁移到 Neo4j（使用 Neo4jGraphDao）。
 *           此接口保留 @Mapper 以维持 Spring 上下文兼容性，但不应用于新代码。
 */
@Mapper
public interface GraphNodeRepository extends LegacyBaseMapper<GraphNode> {

    default List<GraphNode> findByProjectIdAndNodeType(String projectId, String nodeType) {
        return this.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getNodeType, nodeType)
                .list();
    }

    default List<GraphNode> findByProjectId(String projectId) {
        return this.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .list();
    }
}
