package io.github.legacygraph.builder;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * P1-1 补充：节点属性批量回填服务。
 * <p>
 * 为已有图谱中缺少模板属性的核心节点（Controller/Service/Mapper/ApiEndpoint/Table）
 * 批量回填 {@link NodeAttributeTemplates} 定义的必备属性键。
 * </p>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>老数据升级：模板属性引入前的扫描版本</li>
 *   <li>手动触发回填：REST API 调用</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeAttributeBackfillService {

    private final Neo4jGraphDao neo4jGraphDao;

    private static final int BATCH_SIZE = 200;

    /**
     * 批量回填指定项目+版本下所有核心节点的模板属性。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @return 回填的节点总数
     */
    public int backfillAll(String projectId, String versionId) {
        int total = 0;
        for (NodeType type : new NodeType[]{
                NodeType.Controller, NodeType.Service, NodeType.Mapper,
                NodeType.ApiEndpoint, NodeType.Table}) {
            total += backfillByType(projectId, versionId, type);
        }
        log.info("NodeAttributeBackfill completed: {} nodes backfilled (project={}, version={})",
                total, projectId, versionId);
        return total;
    }

    /**
     * 回填指定节点类型的模板属性。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param nodeType  节点类型
     * @return 回填的节点数
     */
    public int backfillByType(String projectId, String versionId, NodeType nodeType) {
        Map<String, Object> template = NodeAttributeTemplates.requiredFor(nodeType);
        if (template.isEmpty()) {
            return 0;
        }

        int count = 0;
        int offset = 0;
        while (true) {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, nodeType.name(), null, null, null, BATCH_SIZE);
            if (nodes == null || nodes.isEmpty()) break;

            for (GraphNode node : nodes) {
                String existingProps = node.getProperties();
                String mergedProps = mergeProperties(template, existingProps);
                if (mergedProps != null && !mergedProps.equals(existingProps)) {
                    // 逐节点更新 properties 字段
                    neo4jGraphDao.setNodeProperty(node.getId(), "properties", mergedProps);
                    count++;
                }
            }

            // 如果返回的节点数不足 BATCH_SIZE，说明已到末尾
            if (nodes.size() < BATCH_SIZE) break;
            offset += BATCH_SIZE;
        }

        log.info("Backfilled {} {} nodes (project={}, version={})",
                count, nodeType.name(), projectId, versionId);
        return count;
    }

    /**
     * 合并模板属性与已有属性。
     * <p>
     * 以模板为基础，解析已有 properties JSON 覆盖到模板上，
     * 确保必备属性键存在且已有值不丢失。
     * </p>
     */
    private String mergeProperties(Map<String, Object> template, String existingPropertiesJson) {
        if (existingPropertiesJson == null || existingPropertiesJson.isBlank()) {
            // 无已有属性，直接返回模板
            return serializeTemplate(template);
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = mapper.readValue(existingPropertiesJson, Map.class);
            // 模板为基础，已有值覆盖
            Map<String, Object> merged = new java.util.LinkedHashMap<>(template);
            merged.putAll(existing);
            return mapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.debug("Failed to merge properties: {}", e.getMessage());
            return serializeTemplate(template);
        }
    }

    private String serializeTemplate(Map<String, Object> template) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(template);
        } catch (Exception e) {
            return null;
        }
    }
}
