package io.github.legacygraph.repository;

import io.github.legacygraph.entity.GraphNode;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface GraphNodeRepository extends LegacyBaseMapper<GraphNode> {

    /**
     * Find all nodes by projectId and nodeType
     */
    default List<GraphNode> findByProjectIdAndNodeType(String projectId, String nodeType) {
        return this.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getNodeType, nodeType)
                .list();
    }

    /**
     * Find all nodes by projectId
     */
    default List<GraphNode> findByProjectId(String projectId) {
        return this.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .list();
    }
}
