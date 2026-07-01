package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Path;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class GraphQueryService {

    // ⚠️ B-H2 Service 绕过 DAO 执行原生 Cypher：本类直接注入 org.neo4j.driver.Driver 并在多处
    //   执行原生 Cypher（neo4jDriver.session()），与 Neo4jGraphDao 职责重叠，DAO 抽象形同虚设。
    //   应将所有 Cypher 收敛到 Neo4jGraphDao，Service 只调 DAO。

    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final FactRepository factRepository;
    private final Driver neo4jDriver;
    private final CacheService cacheService;

    /** 图谱只读结果缓存 TTL（同版本内结果稳定） */
    private static final Duration GRAPH_CACHE_TTL = Duration.ofMinutes(30);

    public GraphQueryService(Neo4jGraphDao neo4jGraphDao,
                            ScanVersionRepository scanVersionRepository,
                            ScanTaskRepository scanTaskRepository,
                            FactRepository factRepository,
                            Driver neo4jDriver,
                            CacheService cacheService) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanVersionRepository = scanVersionRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.factRepository = factRepository;
        this.neo4jDriver = neo4jDriver;
        this.cacheService = cacheService;
    }

    /** 图谱缓存 key：graph:{versionId}:{view}:{paramHash} */
    private String graphKey(String versionId, String view, String... params) {
        String v = normalizeVersionId(versionId);
        return "graph:" + v + ":" + view + ":" + Integer.toHexString(Arrays.hashCode(params));
    }

    /**
     * 按版本失效所有图谱只读缓存（合并/审核确认/重新扫描后调用）。
     */
    public void evictGraphCache(String versionId) {
        if (versionId == null) {
            return;
        }
        cacheService.evictByPrefix("graph:" + normalizeVersionId(versionId) + ":");
    }

    /**
     * 查询接口完整调用链: ApiEndpoint -> Controller -> Method -> Service -> Mapper -> SQL -> Table
     * 增加 projectId/versionId 过滤，避免同名 API 跨版本串数据。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getApiCallChain(String projectId, String versionId, String apiKey) {
        String key = graphKey(versionId, "api-chain", projectId, apiKey);
        return cacheService.getOrLoad(key, List.class, GRAPH_CACHE_TTL,
                () -> getApiCallChainUncached(projectId, versionId, apiKey));
    }

    private List<Map<String, Object>> getApiCallChainUncached(String projectId, String versionId, String apiKey) {
        versionId = normalizeVersionId(versionId);
        String cypher = """
                MATCH p = (api:ApiEndpoint {nodeKey: $apiKey, projectId: $projectId, versionId: $versionId})
                         -[:HANDLED_BY|CALLS|EXECUTES|READS|WRITES*1..8]->(n)
                WHERE n.projectId = $projectId AND n.versionId = $versionId
                RETURN p
                """;

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of(
                    "apiKey", apiKey,
                    "projectId", projectId,
                    "versionId", versionId
            ));
            List<Map<String, Object>> response = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Path path = record.get("p").asPath();
                response.add(pathToMap(path));
            }
            return response;
        }
    }

    /**
     * 查询表被哪些接口影响
     * 增加 projectId/versionId 过滤，避免同名表跨版本串数据。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTableImpact(String projectId, String versionId, String tableName) {
        String key = graphKey(versionId, "table-impact", projectId, tableName);
        return cacheService.getOrLoad(key, List.class, GRAPH_CACHE_TTL,
                () -> getTableImpactUncached(projectId, versionId, tableName));
    }

    private List<Map<String, Object>> getTableImpactUncached(String projectId, String versionId, String tableName) {
        versionId = normalizeVersionId(versionId);
        String cypher = """
                MATCH p = (api:ApiEndpoint {projectId: $projectId, versionId: $versionId})
                         -[:HANDLED_BY|CALLS|EXECUTES|WRITES*1..8]->(t:Table {nodeName: $tableName})
                WHERE t.projectId = $projectId AND t.versionId = $versionId
                RETURN api, p
                """;

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of(
                    "tableName", tableName,
                    "projectId", projectId,
                    "versionId", versionId
            ));
            List<Map<String, Object>> response = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Path path = record.get("p").asPath();
                response.add(pathToMap(path));
            }
            return response;
        }
    }

    /**
     * 获取功能图谱视图
     * 根据模块名称查询完整的功能调用链路图谱。
     * 当 module 未稳定写入时，返回该版本所有 Feature/Page/ApiEndpoint/Service/Repository 子图，
     * 并在结果中标记 moduleMissing = true。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFeatureView(String projectId, String versionId, String module) {
        String key = graphKey(versionId, "feature-view", projectId, module);
        return cacheService.getOrLoad(key, Map.class, GRAPH_CACHE_TTL,
                () -> getFeatureViewUncached(projectId, versionId, module));
    }

    private Map<String, Object> getFeatureViewUncached(String projectId, String versionId, String module) {
        versionId = normalizeVersionId(versionId);
        boolean hasModule = module != null && !module.isBlank();
        String cypher;
        Map<String, Object> result = new HashMap<>();
        result.put("module", module);
        result.put("versionId", versionId);
        result.put("projectId", projectId);

        if (hasModule) {
            cypher = """
                    MATCH (n)
                    WHERE n.projectId = $projectId AND n.versionId = $versionId
                      AND any(label IN labels(n) WHERE label IN ['Feature', 'ApiEndpoint', 'Service', 'Repository'])
                      AND n.module = $module
                    MATCH p = (n)-[:EXPOSED_BY|CALLS|HANDLED_BY|EXECUTES|READS|WRITES*1..10]->(m)
                    WHERE m.projectId = $projectId AND m.versionId = $versionId
                    RETURN DISTINCT p
                    """;
            result.put("moduleMissing", false);
        } else {
            // fallback: 无 module 属性时，返回该版本所有相关类型子图
            cypher = """
                    MATCH (n)
                    WHERE n.projectId = $projectId AND n.versionId = $versionId
                      AND any(label IN labels(n) WHERE label IN ['Feature', 'ApiEndpoint', 'Service', 'Repository', 'Page'])
                    OPTIONAL MATCH p = (n)-[:EXPOSED_BY|CALLS|HANDLED_BY|EXECUTES|READS|WRITES*1..10]->(m)
                    WHERE m.projectId = $projectId AND m.versionId = $versionId
                    RETURN DISTINCT n, p
                    """;
            result.put("moduleMissing", true);
        }

        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", versionId);
            if (hasModule) {
                params.put("module", module);
            }
            Result queryResult = session.run(cypher, params);

            Set<String> nodeIds = new HashSet<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> edges = new ArrayList<>();

            while (queryResult.hasNext()) {
                Record record = queryResult.next();
                org.neo4j.driver.Value pValue = record.get("p");
                if (!pValue.isNull()) {
                    Path path = pValue.asPath();

                    for (var node : path.nodes()) {
                        String nodeId = node.elementId();
                        if (!nodeIds.contains(nodeId)) {
                            nodeIds.add(nodeId);
                            Map<String, Object> nodeMap = new HashMap<>();
                            nodeMap.put("id", nodeId);
                            nodeMap.put("labels", node.labels());
                            nodeMap.put("properties", node.asMap());
                            nodes.add(nodeMap);
                        }
                    }

                    for (var rel : path.relationships()) {
                        Map<String, Object> relMap = new HashMap<>();
                        relMap.put("id", rel.elementId());
                        relMap.put("type", rel.type().toString());
                        relMap.put("startNodeId", rel.startNodeElementId());
                        relMap.put("endNodeId", rel.endNodeElementId());
                        relMap.put("properties", rel.asMap());
                        edges.add(relMap);
                    }
                }
                // 处理 fallback 返回的 standalone 节点
                if (hasModule) continue;
                org.neo4j.driver.Value nValue = record.get("n");
                if (!nValue.isNull()) {
                    var node = nValue.asNode();
                    String nodeId = node.elementId();
                    if (!nodeIds.contains(nodeId)) {
                        nodeIds.add(nodeId);
                        Map<String, Object> nodeMap = new HashMap<>();
                        nodeMap.put("id", nodeId);
                        nodeMap.put("labels", node.labels());
                        nodeMap.put("properties", node.asMap());
                        nodes.add(nodeMap);
                    }
                }
            }

            result.put("nodes", nodes);
            result.put("edges", edges);
            result.put("nodeCount", nodes.size());
            result.put("edgeCount", edges.size());
        }

        return result;
    }

    /**
     * 获取业务图谱视图
     * 根据业务域查询完整的业务对象、流程、规则图谱。
     * 当 domain 属性未稳定写入时，从 BusinessDomain 节点名和
     * BELONGS_TO/PART_OF/IMPLEMENTS 边扩展子图。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBusinessView(String projectId, String versionId, String domain) {
        String key = graphKey(versionId, "business-view", projectId, domain);
        return cacheService.getOrLoad(key, Map.class, GRAPH_CACHE_TTL,
                () -> getBusinessViewUncached(projectId, versionId, domain));
    }

    private Map<String, Object> getBusinessViewUncached(String projectId, String versionId, String domain) {
        versionId = normalizeVersionId(versionId);
        boolean hasDomain = domain != null && !domain.isBlank();
        String cypher;
        Map<String, Object> result = new HashMap<>();
        result.put("domain", domain);
        result.put("versionId", versionId);
        result.put("projectId", projectId);

        if (hasDomain) {
            // 有 domain 参数：按属性过滤 + 从边关系扩展 nodeName 匹配
            cypher = """
                    MATCH (n)
                    WHERE n.projectId = $projectId AND n.versionId = $versionId
                      AND any(label IN labels(n) WHERE label IN ['BusinessDomain', 'BusinessProcess', 'BusinessObject', 'BusinessRule'])
                      AND (n.businessDomain = $domain OR n.domain = $domain OR n.nodeName = $domain)
                    OPTIONAL MATCH p = (n)-[:CONTAINS|USES|DEFINES|REFERENCES*1..8]->(m)
                    WHERE m.projectId = $projectId AND m.versionId = $versionId
                    RETURN DISTINCT n, p
                    """;
            result.put("domainFallback", false);
        } else {
            // fallback: 无 domain 参数时，返回所有业务节点 + BELONGS_TO/PART_OF/IMPLEMENTS 边
            cypher = """
                    MATCH (n)
                    WHERE n.projectId = $projectId AND n.versionId = $versionId
                      AND any(label IN labels(n) WHERE label IN ['BusinessDomain', 'BusinessProcess', 'BusinessObject', 'BusinessRule'])
                    OPTIONAL MATCH p = (n)-[:CONTAINS|USES|DEFINES|REFERENCES|BELONGS_TO|PART_OF|IMPLEMENTS*1..8]->(m)
                    WHERE m.projectId = $projectId AND m.versionId = $versionId
                    RETURN DISTINCT n, p
                    """;
            result.put("domainFallback", true);
        }

        try (Session session = neo4jDriver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", versionId);
            if (hasDomain) {
                params.put("domain", domain);
            }
            Result queryResult = session.run(cypher, params);

            Set<String> nodeIds = new HashSet<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> edges = new ArrayList<>();

            while (queryResult.hasNext()) {
                Record record = queryResult.next();
                org.neo4j.driver.Value pValue = record.get("p");
                if (!pValue.isNull()) {
                    Path path = pValue.asPath();

                    for (var node : path.nodes()) {
                        String nodeId = node.elementId();
                        if (!nodeIds.contains(nodeId)) {
                            nodeIds.add(nodeId);
                            Map<String, Object> nodeMap = new HashMap<>();
                            nodeMap.put("id", nodeId);
                            nodeMap.put("labels", node.labels());
                            nodeMap.put("properties", node.asMap());
                            nodes.add(nodeMap);
                        }
                    }

                    for (var rel : path.relationships()) {
                        Map<String, Object> relMap = new HashMap<>();
                        relMap.put("id", rel.elementId());
                        relMap.put("type", rel.type().toString());
                        relMap.put("startNodeId", rel.startNodeElementId());
                        relMap.put("endNodeId", rel.endNodeElementId());
                        relMap.put("properties", rel.asMap());
                        edges.add(relMap);
                    }
                }
                // 处理 standalone 节点（无出边的 n）
                org.neo4j.driver.Value nValue = record.get("n");
                if (!nValue.isNull()) {
                    var node = nValue.asNode();
                    String nodeId = node.elementId();
                    if (!nodeIds.contains(nodeId)) {
                        nodeIds.add(nodeId);
                        Map<String, Object> nodeMap = new HashMap<>();
                        nodeMap.put("id", nodeId);
                        nodeMap.put("labels", node.labels());
                        nodeMap.put("properties", node.asMap());
                        nodes.add(nodeMap);
                    }
                }
            }

            result.put("nodes", nodes);
            result.put("edges", edges);
            result.put("nodeCount", nodes.size());
            result.put("edgeCount", edges.size());
        }

        return result;
    }

    /**
     * 获取版本中所有数据库表节点（仅节点，不含边，轻量查询）
     * 用于数据血缘页面快速加载表列表，避免加载全量统一图谱。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTablesNodes(String projectId, String versionId) {
        String key = graphKey(versionId, "tables", projectId);
        return cacheService.getOrLoad(key, List.class, GRAPH_CACHE_TTL,
                () -> getTablesNodesUncached(projectId, versionId));
    }

    private List<Map<String, Object>> getTablesNodesUncached(String projectId, String versionId) {
        String normalizedVersionId = versionId != null ? versionId.replace("-", "") : null;
        List<GraphNode> tableNodes = neo4jGraphDao.queryNodes(
                projectId, normalizedVersionId, "Table", null, null, null, 0);

        return tableNodes.stream().map(node -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", node.getId());
            m.put("key", node.getNodeKey());
            m.put("label", node.getDisplayName());
            m.put("name", node.getNodeName());          // Cypher 查询匹配用 nodeName
            m.put("type", node.getNodeType());
            m.put("confidence", node.getConfidence());
            m.put("status", node.getStatus());
            m.put("columnCount", 0);       // 前端自行扩展
            m.put("relationCount", 0);
            return m;
        }).toList();
    }

    /**
     * 获取统一图谱全量数据（支持按状态过滤、返回空视图结构化原因）
     * 从 Neo4j 查询指定扫描版本的所有节点和边，按置信度+状态过滤后返回
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUnifiedGraph(String versionId, Double minConfidence, String statusFilter) {
        String key = graphKey(versionId, "unified",
                String.valueOf(minConfidence), String.valueOf(statusFilter));
        return cacheService.getOrLoad(key, Map.class, GRAPH_CACHE_TTL,
                () -> getUnifiedGraphUncached(versionId, minConfidence, statusFilter));
    }

    private Map<String, Object> getUnifiedGraphUncached(String versionId, Double minConfidence, String statusFilter) {
        // 标准化 versionId：Neo4j 存储无横线格式，PG 查询用原始带横线格式
        String neo4jVersionId = versionId != null ? versionId.replace("-", "") : null;

        // 从 scanVersionRepository 获取 projectId（用原始带横线 versionId 查询 PG UUID 列）
        ScanVersion version = scanVersionRepository.lambdaQuery()
                .eq(ScanVersion::getId, versionId)
                .one();
        if (version == null) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("versionId", versionId);
            errorResult.put("emptyReasons", List.of("版本不存在"));
            return errorResult;
        }
        String projectId = version.getProjectId();

        // 从Neo4j查询节点（用无横线 versionId）
        String effectiveStatus = (statusFilter != null && !statusFilter.isBlank()) ? statusFilter : null;
        List<GraphNode> nodes = neo4jGraphDao.queryNodes(projectId, neo4jVersionId, null, null, minConfidence, effectiveStatus, 0);

        // 从Neo4j查询边（用无横线 versionId）
        List<GraphEdge> edges = neo4jGraphDao.queryEdges(projectId, neo4jVersionId, minConfidence, effectiveStatus, 0);

        Map<String, Object> result = new HashMap<>();
        result.put("versionId", versionId);
        result.put("statusFilter", statusFilter);
        result.put("nodes", nodes.stream().map(node -> {
            Map<String, Object> nodeMap = new HashMap<>();
            nodeMap.put("id", node.getId());
            nodeMap.put("key", node.getNodeKey());
            nodeMap.put("label", node.getDisplayName());
            nodeMap.put("type", node.getNodeType());
            nodeMap.put("confidence", node.getConfidence());
            nodeMap.put("status", node.getStatus());
            nodeMap.put("description", node.getDescription());
            nodeMap.put("sourcePath", node.getSourcePath());
            nodeMap.put("sourceType", node.getSourceType());
            nodeMap.put("verifiedScore", node.getVerifiedScore());
            nodeMap.put("runtimeVerified", node.getRuntimeVerified());
            nodeMap.put("lastSeenAt", node.getLastSeenAt() != null ? node.getLastSeenAt().toString() : null);
            nodeMap.put("traceCount", node.getTraceCount());
            return nodeMap;
        }).toList());
        result.put("edges", edges.stream().map(edge -> {
            Map<String, Object> edgeMap = new HashMap<>();
            edgeMap.put("id", edge.getId());
            edgeMap.put("source", edge.getFromNodeId());
            edgeMap.put("target", edge.getToNodeId());
            edgeMap.put("type", edge.getEdgeType());
            edgeMap.put("label", getEdgeLabel(edge.getEdgeType()));
            edgeMap.put("confidence", edge.getConfidence());
            edgeMap.put("status", edge.getStatus());
            return edgeMap;
        }).toList());
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());

        // 空视图时返回结构化原因
        if (nodes.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            // 检查 Neo4j 连通性
            try (org.neo4j.driver.Session s = neo4jDriver.session()) {
                var ping = s.run("RETURN 1 AS ok").single().get("ok").asInt();
                if (ping != 1) {
                    reasons.add("Neo4j 连接异常");
                }
            } catch (Exception neo4jEx) {
                reasons.add("Neo4j 不可达: " + neo4jEx.getMessage());
            }
            // 检查是否有该版本的任何节点（不过滤状态）
            long totalNodes = neo4jGraphDao.countNodes(projectId, neo4jVersionId, null);
            if (totalNodes == 0) {
                reasons.add("该版本尚未扫描或扫描未生成图谱数据");
            } else {
                reasons.add("有 " + totalNodes + " 个节点但未匹配当前过滤条件（置信度>=" + minConfidence +
                        (statusFilter != null ? ", 状态=" + statusFilter : "") + "）");
            }
            // 检查是否同步到 Neo4j
            if (totalNodes > 0) {
                long confirmedNodes = neo4jGraphDao.countNodes(projectId, neo4jVersionId, "CONFIRMED");
                long pendingNodes = totalNodes - confirmedNodes;
                if (pendingNodes > 0) {
                    reasons.add(pendingNodes + " 个节点状态为 PENDING_CONFIRM");
                }
            }
            // 交叉验证：检查该版本是否有事实数据（扫描是否真的执行了）
            long factCount = factRepository.lambdaQuery()
                    .eq(Fact::getVersionId, versionId)
                    .count();
            if (factCount == 0) {
                reasons.add("该版本无事实数据，扫描可能未成功执行或代码目录为空");
            } else {
                reasons.add("有 " + factCount + " 条事实数据但未生成图谱节点（可能 Neo4j 写入失败）");
            }
            result.put("emptyReasons", reasons);
        }

        return result;
    }

    /**
     * 获取项目扫描版本列表（分页），包含进度、任务统计和节点/边统计
     */
    public PageResult<Map<String, Object>> getScanVersions(String projectId, int pageNum, int pageSize) {
        Page<ScanVersion> page = new Page<>(pageNum, pageSize);
        Page<ScanVersion> versionPage = scanVersionRepository.lambdaQuery()
                .eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getCreatedAt)
                .page(page);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScanVersion v : versionPage.getRecords()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", v.getId());
            map.put("versionNo", v.getVersionNo());
            map.put("versionNumber", v.getVersionNo());
            map.put("versionName", v.getVersionNo());
            map.put("branchName", v.getBranchName());
            map.put("commitId", v.getCommitId());
            map.put("scanStatus", v.getScanStatus());
            map.put("scanType", v.getScanScope());
            map.put("startedAt", v.getStartedAt() != null ? v.getStartedAt().toString() : null);
            map.put("finishedAt", v.getFinishedAt() != null ? v.getFinishedAt().toString() : null);
            map.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
            map.put("createdBy", "-");

            // 查询该版本的扫描子任务，计算进度和当前阶段
            List<ScanTask> tasks = scanTaskRepository.lambdaQuery()
                    .eq(ScanTask::getVersionId, v.getId())
                    .list();

            int totalTasks = tasks.size();
            long completedTasks = tasks.stream()
                    .filter(t -> "SUCCESS".equals(t.getTaskStatus()))
                    .count();
            long failedTasks = tasks.stream()
                    .filter(t -> "FAILED".equals(t.getTaskStatus()))
                    .count();

            int progress = totalTasks > 0 ? (int) (completedTasks * 100 / totalTasks) : 0;

            // 如果版本已完成，进度为100
            if ("SUCCESS".equals(v.getScanStatus()) || "COMPLETED".equals(v.getScanStatus())) {
                progress = 100;
            }

            map.put("progress", progress);
            map.put("taskCount", totalTasks);
            map.put("completedTaskCount", (int) completedTasks);
            map.put("failedTaskCount", (int) failedTasks);

            // 当前阶段：取第一个非 SUCCESS 的子任务类型
            String stage = tasks.stream()
                    .filter(t -> !"SUCCESS".equals(t.getTaskStatus()))
                    .findFirst()
                    .map(ScanTask::getTaskType)
                    .orElse(totalTasks > 0 ? "COMPLETED" : "-");
            map.put("stage", stage);

            // 耗时计算
            long duration = 0;
            if (v.getStartedAt() != null) {
                LocalDateTime end = v.getFinishedAt() != null ? v.getFinishedAt() : LocalDateTime.now();
                duration = Duration.between(v.getStartedAt(), end).getSeconds();
            }
            map.put("duration", duration);

            // 统计该版本的节点和边数量（从 Neo4j 查询）
            long nodeCount = neo4jGraphDao.countNodes(projectId, v.getId(), null);
            long edgeCount = neo4jGraphDao.countEdges(projectId, v.getId(), null);
            map.put("nodeCount", nodeCount);
            map.put("edgeCount", edgeCount);

            // 统计事实数
            long factCount = factRepository.lambdaQuery()
                    .eq(Fact::getVersionId, v.getId())
                    .count();
            map.put("factCount", factCount);

            result.add(map);
        }

        return PageResult.of(result, versionPage.getTotal(), pageNum, pageSize);
    }

    /**
     * 获取关系类型显示标签
     */
    private String getEdgeLabel(String edgeType) {
        return switch (edgeType) {
            case "CONTAINS" -> "包含";
            case "CALLS" -> "调用";
            case "HANDLED_BY" -> "处理";
            case "EXECUTES" -> "执行";
            case "READS" -> "读取";
            case "WRITES" -> "写入";
            case "HAS_COLUMN" -> "字段";
            default -> edgeType;
        };
    }

    private Map<String, Object> pathToMap(Path path) {
        Map<String, Object> pathMap = new HashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> relationships = new ArrayList<>();

        Iterable<org.neo4j.driver.types.Node> pathNodes = path.nodes();
        if (pathNodes != null) {
            for (var node : pathNodes) {
                nodes.add(neo4jNodeToMap(node));
            }
        }
        Iterable<org.neo4j.driver.types.Relationship> pathRelationships = path.relationships();
        if (pathRelationships != null) {
            for (var rel : pathRelationships) {
                relationships.add(neo4jRelationshipToMap(rel));
            }
        }

        pathMap.put("nodes", nodes);
        pathMap.put("edges", relationships);
        pathMap.put("relationships", relationships);
        return pathMap;
    }

    private Map<String, Object> neo4jNodeToMap(org.neo4j.driver.types.Node node) {
        Map<String, Object> nodeMap = new HashMap<>();
        nodeMap.put("id", neo4jNodeId(node));
        nodeMap.put("labels", node.labels());
        nodeMap.put("properties", node.asMap());
        return nodeMap;
    }

    private Map<String, Object> neo4jRelationshipToMap(org.neo4j.driver.types.Relationship rel) {
        Map<String, Object> relMap = new HashMap<>();
        relMap.put("id", neo4jRelationshipId(rel));
        relMap.put("type", rel.type());
        relMap.put("source", rel.startNodeElementId());
        relMap.put("target", rel.endNodeElementId());
        relMap.put("startNodeId", rel.startNodeElementId());
        relMap.put("endNodeId", rel.endNodeElementId());
        relMap.put("properties", rel.asMap());
        return relMap;
    }

    private String neo4jNodeId(org.neo4j.driver.types.Node node) {
        String elementId = node.elementId();
        if (elementId != null && !elementId.isBlank()) {
            return elementId;
        }
        return String.valueOf(node.id());
    }

    private String neo4jRelationshipId(org.neo4j.driver.types.Relationship rel) {
        String elementId = rel.elementId();
        if (elementId != null && !elementId.isBlank()) {
            return elementId;
        }
        return String.valueOf(rel.id());
    }

    /**
     * 标准化 versionId：去掉横线以匹配 Neo4j 存储格式。
     * PG JDBC 返回带横线的 UUID，Neo4j 写入时使用 MyBatis-Plus 生成的无横线格式。
     */
    private String normalizeVersionId(String versionId) {
        return versionId != null ? versionId.replace("-", "") : null;
    }

    // ==================== 漂移队列 ====================

    /**
     * 获取漂移队列：静态-only、运行时-only、文档-only、低置信等待处理项。
     * @param projectId 项目ID
     * @param type 过滤类型（all/static_only/dynamic_only/doc_only/low_confidence）
     */
    public Map<String, Object> getDriftQueue(String projectId, String type) {
        List<Map<String, Object>> allItems = new ArrayList<>();

        List<GraphEdge> edges = neo4jGraphDao.queryEdges(projectId, null, null, null, 200);
        for (GraphEdge edge : edges) {
            if ("static_only_candidate".equals(edge.getRelationStatus())) {
                allItems.add(edgeDriftItem(edge, "static_only", "静态图谱存在但运行时尚未观测", "MEDIUM"));
            } else if ("dynamic_only_candidate".equals(edge.getRelationStatus())
                    || "RUNTIME_TRACE".equals(edge.getSourceType())) {
                allItems.add(edgeDriftItem(edge, "dynamic_only", "运行时观测到但静态图谱未确认", "HIGH"));
            }
        }

        List<GraphNode> nodes = neo4jGraphDao.queryNodes(projectId, null, null, null, null, null, 200);
        for (GraphNode node : nodes) {
            if ("DOC_AI".equals(node.getSourceType())) {
                allItems.add(nodeDriftItem(node, "doc_only", "文档 AI 节点需要与代码/运行时证据对齐", "MEDIUM"));
            }
            BigDecimal confidence = node.getConfidence();
            if (confidence != null && confidence.compareTo(BigDecimal.valueOf(0.5)) < 0) {
                allItems.add(nodeDriftItem(node, "low_confidence", "低置信节点需要人工确认", "HIGH"));
            }
        }

        String normalizedType = type == null || type.isBlank() ? "all" : type;
        List<Map<String, Object>> items = "all".equals(normalizedType)
                ? allItems
                : allItems.stream()
                .filter(item -> normalizedType.equals(item.get("driftType")))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("summary", driftSummary(allItems));
        return response;
    }

    private Map<String, Object> edgeDriftItem(GraphEdge edge, String driftType,
                                              String description, String severity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", edge.getId());
        item.put("elementId", edge.getId());
        item.put("targetType", "EDGE");
        item.put("driftType", driftType);
        item.put("elementName", edge.getEdgeKey() != null ? edge.getEdgeKey() : edge.getEdgeType());
        item.put("description", description);
        item.put("severity", severity);
        item.put("confidence", edge.getConfidence());
        item.put("createdAt", edge.getCreatedAt());
        return item;
    }

    private Map<String, Object> nodeDriftItem(GraphNode node, String driftType,
                                              String description, String severity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", node.getId());
        item.put("elementId", node.getId());
        item.put("targetType", "NODE");
        item.put("driftType", driftType);
        item.put("elementName", node.getDisplayName() != null ? node.getDisplayName() : node.getNodeName());
        item.put("description", description);
        item.put("severity", severity);
        item.put("confidence", node.getConfidence());
        item.put("createdAt", node.getCreatedAt());
        return item;
    }

    private Map<String, Object> driftSummary(List<Map<String, Object>> items) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("staticOnly", countDrift(items, "static_only"));
        summary.put("dynamicOnly", countDrift(items, "dynamic_only"));
        summary.put("docOnly", countDrift(items, "doc_only"));
        summary.put("lowConfidence", countDrift(items, "low_confidence"));
        return summary;
    }

    private long countDrift(List<Map<String, Object>> items, String driftType) {
        return items.stream().filter(item -> driftType.equals(item.get("driftType"))).count();
    }
}
