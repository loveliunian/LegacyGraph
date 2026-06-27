package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.GraphEdge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface GraphEdgeRepository extends BaseMapper<GraphEdge> {

    /**
     * 获取节点的所有邻居节点ID（包括入边和出边）
     */
    @Select("SELECT DISTINCT " +
            "CASE WHEN from_node_id = #{nodeId} THEN to_node_id ELSE from_node_id END " +
            "FROM lg_graph_edge " +
            "WHERE version_id = #{versionId} " +
            "AND (from_node_id = #{nodeId} OR to_node_id = #{nodeId}) " +
            "AND deleted = 0")
    List<String> findNeighborNodeIds(@Param("nodeId") String nodeId, @Param("versionId") String versionId);

    /**
     * 更新所有 fromNodeId = oldNodeId 的关系为 newNodeId
     */
    @Update("UPDATE lg_graph_edge " +
            "SET from_node_id = #{newNodeId} " +
            "WHERE from_node_id = #{oldNodeId} AND version_id = #{versionId} AND deleted = 0")
    int updateFromNodeId(@Param("oldNodeId") String oldNodeId,
                         @Param("newNodeId") String newNodeId,
                         @Param("versionId") String versionId);

    /**
     * 更新所有 toNodeId = oldNodeId 的关系为 newNodeId
     */
    @Update("UPDATE lg_graph_edge " +
            "SET to_node_id = #{newNodeId} " +
            "WHERE to_node_id = #{oldNodeId} AND version_id = #{versionId} AND deleted = 0")
    int updateToNodeId(@Param("oldNodeId") String oldNodeId,
                       @Param("newNodeId") String newNodeId,
                       @Param("versionId") String versionId);
}
