package io.github.legacygraph.federation;

import io.github.legacygraph.entity.Project;
import io.github.legacygraph.service.scan.ProjectService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 跨仓库影响分析服务。
 * <p>
 * 从 Neo4j 图谱中提取表访问、API 调用、消息 Topic 等跨项目数据，
 * 通过 {@link CrossRepositoryLinker} 生成跨仓库影响链路。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossRepoImpactService {

    private final Driver neo4jDriver;
    private final CrossRepositoryLinker linker;
    private final ProjectService projectService;

    /**
     * 获取指定项目的跨仓库影响链路。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID（可选）
     * @return 影响链路列表
     */
    public List<CrossRepoImpactChain> getCrossRepoImpact(String projectId, String versionId) {
        String normalizedVersionId = normalizeId(versionId);

        // 1. 从 Neo4j 收集数据
        List<CrossRepositoryLinker.TableAccess> tableAccesses = queryTableAccesses(normalizedVersionId);
        List<CrossRepositoryLinker.ExternalApiCall> apiCalls = queryExternalApiCalls(normalizedVersionId);
        List<CrossRepositoryLinker.TopicAccess> topicAccesses = queryTopicAccesses(normalizedVersionId);

        log.info("跨仓库数据收集完成: tableAccesses={}, apiCalls={}, topicAccesses={}",
                tableAccesses.size(), apiCalls.size(), topicAccesses.size());

        // 2. 运行链接器
        List<CrossRepositoryLinkCandidate> allCandidates = linker.linkAll(tableAccesses, apiCalls, topicAccesses);

        // 3. 过滤只保留与当前项目相关的链路
        List<CrossRepositoryLinkCandidate> relevant = allCandidates.stream()
                .filter(c -> c.fromProjectId().equals(projectId) || c.toProjectId().equals(projectId))
                .toList();

        log.info("跨仓库影响分析完成: total={}, relevant={}", allCandidates.size(), relevant.size());

        // 4. 构建响应链路
        return relevant.stream()
                .map(c -> buildChain(c, normalizedVersionId))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ===================== Neo4j 查询 =====================

    /**
     * 查询所有项目的表访问记录（READS / WRITES 边指向 Table 节点）。
     */
    private List<CrossRepositoryLinker.TableAccess> queryTableAccesses(String versionId) {
        List<CrossRepositoryLinker.TableAccess> result = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (n)-[r:READS|WRITES]->(t:Table)
                    WHERE n.projectId IS NOT NULL
                    AND t.nodeName IS NOT NULL
                    AND ($versionId IS NULL OR n.versionId = $versionId)
                    RETURN DISTINCT n.projectId AS projectId,
                           n.nodeKey AS nodeKey,
                           t.nodeName AS tableName,
                           type(r) AS accessType
                    """;
            Result rs = session.run(cypher, Map.of("versionId", versionId));
            while (rs.hasNext()) {
                var rec = rs.next();
                result.add(new CrossRepositoryLinker.TableAccess(
                        rec.get("projectId").asString(),
                        rec.get("nodeKey").asString(),
                        rec.get("tableName").asString(),
                        rec.get("accessType").asString()  // "READS" -> "READ", "WRITES" -> "WRITE" via linker
                ));
            }
        } catch (Exception e) {
            log.warn("查询表访问记录失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 查询所有项目的外部 API 调用记录（CALLS 边指向 Controller/ApiEndpoint 节点）。
     */
    private List<CrossRepositoryLinker.ExternalApiCall> queryExternalApiCalls(String versionId) {
        List<CrossRepositoryLinker.ExternalApiCall> result = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            // 查询 provider（被调用的 Controller/ApiEndpoint 作为提供者）
            String providerCypher = """
                    MATCH (caller)-[r:CALLS]->(api)
                    WHERE (api:Controller OR api:ApiEndpoint)
                    AND api.projectId IS NOT NULL
                    AND ($versionId IS NULL OR api.versionId = $versionId)
                    RETURN DISTINCT api.projectId AS projectId,
                           api.nodeKey AS nodeKey,
                           coalesce(api.nodeName, api.id) AS apiEndpoint,
                           true AS isProvider
                    """;
            Result rs1 = session.run(providerCypher, Map.of("versionId", versionId));
            while (rs1.hasNext()) {
                var rec = rs1.next();
                result.add(new CrossRepositoryLinker.ExternalApiCall(
                        rec.get("projectId").asString(),
                        rec.get("nodeKey").asString(),
                        rec.get("apiEndpoint").asString(),
                        null,  // httpMethod 暂不可确定
                        rec.get("isProvider").asBoolean()
                ));
            }

            // 查询 caller（调用 Controller/ApiEndpoint 的节点作为调用者）
            String callerCypher = """
                    MATCH (caller)-[r:CALLS]->(api)
                    WHERE (api:Controller OR api:ApiEndpoint)
                    AND caller.projectId IS NOT NULL
                    AND ($versionId IS NULL OR caller.versionId = $versionId)
                    RETURN DISTINCT caller.projectId AS projectId,
                           caller.nodeKey AS nodeKey,
                           coalesce(api.nodeName, api.id) AS apiEndpoint,
                           false AS isProvider
                    """;
            Result rs2 = session.run(callerCypher, Map.of("versionId", versionId));
            while (rs2.hasNext()) {
                var rec = rs2.next();
                result.add(new CrossRepositoryLinker.ExternalApiCall(
                        rec.get("projectId").asString(),
                        rec.get("nodeKey").asString(),
                        rec.get("apiEndpoint").asString(),
                        null,
                        rec.get("isProvider").asBoolean()
                ));
            }
        } catch (Exception e) {
            log.warn("查询外部 API 调用记录失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 查询所有项目的消息 Topic 访问记录。
     * TRIGGERS 边 → PRODUCE, CONSUMES 边 → CONSUME。
     */
    private List<CrossRepositoryLinker.TopicAccess> queryTopicAccesses(String versionId) {
        List<CrossRepositoryLinker.TopicAccess> result = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            // Producer（TRIGGERS 边指向 MQTopic）
            String producerCypher = """
                    MATCH (n)-[r:TRIGGERS]->(t:MQTopic)
                    WHERE n.projectId IS NOT NULL
                    AND ($versionId IS NULL OR n.versionId = $versionId)
                    RETURN DISTINCT n.projectId AS projectId,
                           n.nodeKey AS nodeKey,
                           coalesce(t.nodeName, '') AS topicName,
                           'PRODUCE' AS accessType
                    """;
            Result rs1 = session.run(producerCypher, Map.of("versionId", versionId));
            while (rs1.hasNext()) {
                var rec = rs1.next();
                result.add(new CrossRepositoryLinker.TopicAccess(
                        rec.get("projectId").asString(),
                        rec.get("nodeKey").asString(),
                        rec.get("topicName").asString(),
                        rec.get("accessType").asString()
                ));
            }

            // Consumer（CONSUMES 边指向 MQTopic）
            String consumerCypher = """
                    MATCH (n)-[r:CONSUMES]->(t:MQTopic)
                    WHERE n.projectId IS NOT NULL
                    AND ($versionId IS NULL OR n.versionId = $versionId)
                    RETURN DISTINCT n.projectId AS projectId,
                           n.nodeKey AS nodeKey,
                           coalesce(t.nodeName, '') AS topicName,
                           'CONSUME' AS accessType
                    """;
            Result rs2 = session.run(consumerCypher, Map.of("versionId", versionId));
            while (rs2.hasNext()) {
                var rec = rs2.next();
                result.add(new CrossRepositoryLinker.TopicAccess(
                        rec.get("projectId").asString(),
                        rec.get("nodeKey").asString(),
                        rec.get("topicName").asString(),
                        rec.get("accessType").asString()
                ));
            }
        } catch (Exception e) {
            log.warn("查询消息 Topic 访问记录失败: {}", e.getMessage());
        }
        return result;
    }

    // ===================== 链路构建 =====================

    /**
     * 将候选关系构建为影响链路。
     */
    private CrossRepoImpactChain buildChain(CrossRepositoryLinkCandidate candidate, String versionId) {
        try {
            NodeInfo fromNode = lookupNode(candidate.fromNodeKey(), versionId);
            NodeInfo toNode = lookupNode(candidate.toNodeKey(), versionId);

            String sourceRepoName = getRepoName(candidate.fromProjectId());
            String targetRepoName = getRepoName(candidate.toProjectId());

            // 传播路径：从源节点到目标节点
            List<NodeInfo> chain = new ArrayList<>();
            if (fromNode != null) {
                fromNode.setRepo(sourceRepoName);
                chain.add(fromNode);
            }
            if (toNode != null) {
                toNode.setRepo(targetRepoName);
                chain.add(toNode);
            }

            CrossRepoImpactChain impactChain = new CrossRepoImpactChain();
            impactChain.setSourceRepo(sourceRepoName);
            impactChain.setSourceNode(fromNode);
            impactChain.setTargetRepo(targetRepoName);
            impactChain.setTargetNode(toNode);
            impactChain.setChain(chain);
            impactChain.setImpactLevel(confidenceToLevel(candidate.confidence()));

            return impactChain;
        } catch (Exception e) {
            log.warn("构建影响链路失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据 nodeKey 在 Neo4j 中查找节点信息。
     */
    private NodeInfo lookupNode(String nodeKey, String versionId) {
        if (nodeKey == null || nodeKey.isBlank()) return null;
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (n)
                    WHERE n.nodeKey = $nodeKey
                    AND ($versionId IS NULL OR n.versionId = $versionId)
                    RETURN coalesce(n.nodeName, n.displayName, n.id) AS name,
                           labels(n) AS type,
                           n.id AS id
                    LIMIT 1
                    """;
            Result rs = session.run(cypher, Map.of("nodeKey", nodeKey, "versionId", versionId));
            if (rs.hasNext()) {
                var rec = rs.next();
                NodeInfo info = new NodeInfo();
                info.setId(rec.get("id").asString());
                info.setName(rec.get("name").asString());
                List<Object> labels = rec.get("type").asList();
                info.setType(labels.isEmpty() ? "" : labels.get(0).toString());
                return info;
            }
        } catch (Exception e) {
            log.warn("查找节点失败 nodeKey={}: {}", nodeKey, e.getMessage());
        }
        return null;
    }

    /**
     * 获取仓库（项目）的可读名称。
     */
    private String getRepoName(String projectId) {
        try {
            Project project = projectService.getById(projectId);
            if (project != null && project.getProjectName() != null) {
                return project.getProjectName();
            }
        } catch (Exception e) {
            log.debug("查找项目名称失败 projectId={}: {}", projectId, e.getMessage());
        }
        return projectId;
    }

    /**
     * 置信度 → 影响等级。
     */
    private static String confidenceToLevel(double confidence) {
        if (confidence >= 0.9) return "HIGH";
        if (confidence >= 0.7) return "MEDIUM";
        return "LOW";
    }

    /**
     * 归一化版本 ID（去除横线）。
     */
    private static String normalizeId(String id) {
        if (id == null) return null;
        return id.replace("-", "");
    }

    // ===================== DTO =====================

    @Data
    public static class CrossRepoImpactChain {
        private String sourceRepo;
        private NodeInfo sourceNode;
        private String targetRepo;
        private NodeInfo targetNode;
        private List<NodeInfo> chain;
        private String impactLevel;
    }

    @Data
    public static class NodeInfo {
        private String id;
        private String name;
        private String type;
        private String repo;
    }
}
