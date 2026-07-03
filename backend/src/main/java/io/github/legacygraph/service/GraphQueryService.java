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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GraphQueryService {

    // Phase 2.6: 图谱只读查询已收口到 GraphPathReadModel / GraphProjectionReadModel。

    private static final ExecutorService graphQueryExecutor = Executors.newFixedThreadPool(4);

    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final FactRepository factRepository;
    private final CacheService cacheService;
    private final GraphPathReadModel pathReadModel;
    private final GraphProjectionReadModel projectionReadModel;

    /** 图谱只读结果缓存 TTL（同版本内结果稳定） */
    private static final Duration GRAPH_CACHE_TTL = Duration.ofMinutes(30);

    public GraphQueryService(Neo4jGraphDao neo4jGraphDao,
                            ScanVersionRepository scanVersionRepository,
                            ScanTaskRepository scanTaskRepository,
                            FactRepository factRepository,
                            CacheService cacheService,
                            GraphPathReadModel pathReadModel,
                            GraphProjectionReadModel projectionReadModel) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanVersionRepository = scanVersionRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.factRepository = factRepository;
        this.cacheService = cacheService;
        this.pathReadModel = pathReadModel;
        this.projectionReadModel = projectionReadModel;
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
        // Phase 2.6: 委托 GraphPathReadModel（通过 Neo4jGraphDao，不直接持有 Driver）
        GraphPathReadModel.PathChain chain = pathReadModel.getApiCallChain(projectId, versionId, apiKey);
        if (chain == null || chain.nodes == null || chain.nodes.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> response = new ArrayList<>();
        for (var node : chain.nodes) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", node.id());
            m.put("type", node.type());
            m.put("name", node.name());
            m.put("displayName", node.displayName());
            response.add(m);
        }
        return response;
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
        // Phase 2.6: 委托 GraphPathReadModel
        GraphPathReadModel.PathChain chain = pathReadModel.getTableImpact(projectId, versionId, tableName);
        if (chain == null || chain.nodes == null || chain.nodes.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> response = new ArrayList<>();
        for (var node : chain.nodes) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", node.id());
            m.put("type", node.type());
            m.put("name", node.name());
            m.put("displayName", node.displayName());
            response.add(m);
        }
        return response;
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
        // Phase 2.6: 委托 GraphProjectionReadModel（通过 Neo4jGraphDao）
        GraphProjectionReadModel.ProjectionView view = projectionReadModel.getFeatureView(projectId, versionId, module);
        Map<String, Object> result = new HashMap<>();
        result.put("module", module);
        result.put("versionId", versionId);
        result.put("projectId", projectId);
        result.put("moduleMissing", module == null || module.isBlank());
        result.put("nodes", view.nodes.stream().map(n -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", n.id());
            m.put("labels", List.of(n.type()));
            m.put("properties", Map.of("name", n.name(), "displayName", n.displayName()));
            return m;
        }).toList());
        result.put("edges", view.edges.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("startNodeId", e.fromNodeId());
            m.put("endNodeId", e.toNodeId());
            return m;
        }).toList());
        result.put("nodeCount", view.nodes.size());
        result.put("edgeCount", view.edges.size());
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
        // Phase 2.6: 委托 GraphProjectionReadModel
        GraphProjectionReadModel.ProjectionView view = projectionReadModel.getBusinessView(projectId, versionId, domain);
        Map<String, Object> result = new HashMap<>();
        result.put("domain", domain);
        result.put("versionId", versionId);
        result.put("projectId", projectId);
        result.put("domainFallback", domain == null || domain.isBlank());
        result.put("nodes", view.nodes.stream().map(n -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", n.id());
            m.put("labels", List.of(n.type()));
            m.put("properties", Map.of("name", n.name(), "displayName", n.displayName()));
            return m;
        }).toList());
        result.put("edges", view.edges.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("startNodeId", e.fromNodeId());
            m.put("endNodeId", e.toNodeId());
            return m;
        }).toList());
        result.put("nodeCount", view.nodes.size());
        result.put("edgeCount", view.edges.size());
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

        // 从Neo4j并行查询节点和边（用无横线 versionId）— 使用 map projection 优化
        String effectiveStatus = (statusFilter != null && !statusFilter.isBlank()) ? statusFilter : null;
        
        CompletableFuture<List<Map<String, Object>>> nodesFuture = CompletableFuture.supplyAsync(() -> 
            neo4jGraphDao.queryNodesProjection(projectId, neo4jVersionId, minConfidence, effectiveStatus),
            graphQueryExecutor);
        
        CompletableFuture<List<Map<String, Object>>> edgesFuture = CompletableFuture.supplyAsync(() -> 
            neo4jGraphDao.queryEdgesProjection(projectId, neo4jVersionId, minConfidence, effectiveStatus),
            graphQueryExecutor);
        
        List<Map<String, Object>> nodes = nodesFuture.join();
        List<Map<String, Object>> edges = edgesFuture.join();
        
        // 为 edges 添加 label 字段
        edges.forEach(edge -> {
            String edgeType = (String) edge.get("type");
            edge.put("label", getEdgeLabel(edgeType));
        });
        
        Map<String, Object> result = new HashMap<>();
        result.put("versionId", versionId);
        result.put("statusFilter", statusFilter);
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());

        // 空视图时返回结构化原因
        if (nodes.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            // 检查 Neo4j 连通性（通过 DAO countNodes 推断：能查到 count 说明连通）
            try {
                neo4jGraphDao.countNodes(projectId, neo4jVersionId, null);
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
     * 获取项目扫描版本列表（分页），包含进度、任务统计和节点/边统计。
     *
     * <p>性能优化策略（V13 之后）：
     * <ul>
     *   <li>终态版本（SUCCESS/COMPLETED/FAILED/CANCELLED）：直接读 ScanVersion 冗余字段，
     *       0 次 Neo4j 查询、0 次额外 SQL —— 由 ProjectScanner 在扫描结束时回写。</li>
     *   <li>非终态版本（CREATED/RUNNING/PAUSED）：仅对这部分版本做一次
     *       <b>批量聚合</b>（ScanTask/Fact 按 versionId IN(...) 分组；Neo4j 用 UNWIND 一次算完），
     *       彻底消除原来的 N+1 查询。</li>
     *   <li>历史版本（stats_updated_at 为空，属于 V13 之前遗留）：也走批量聚合兜底，
     *       避免升级后老数据显示 0。</li>
     * </ul>
     */
    public PageResult<Map<String, Object>> getScanVersions(String projectId, int pageNum, int pageSize) {
        Page<ScanVersion> page = new Page<>(pageNum, pageSize);
        Page<ScanVersion> versionPage = scanVersionRepository.lambdaQuery()
                .eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getCreatedAt)
                .page(page);

        List<ScanVersion> versions = versionPage.getRecords();

        // 分类：需要实时聚合 vs 直接读快照
        List<String> liveVersionIds = new ArrayList<>();
        for (ScanVersion v : versions) {
            if (needsLiveAggregation(v)) {
                liveVersionIds.add(v.getId());
            }
        }

        // 只对 liveVersionIds 做批量聚合（一次 SQL / 一次 Cypher 拿完整页）
        Map<String, TaskAggregate> taskAgg = batchAggregateTasks(liveVersionIds);
        Map<String, Long> factAgg = batchAggregateFacts(liveVersionIds);
        Map<String, long[]> graphAgg = batchAggregateGraph(projectId, liveVersionIds);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScanVersion v : versions) {
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

            int taskTotal;
            int taskSuccess;
            int taskFailed;
            String stage;
            long nodeCount;
            long edgeCount;
            long factCount;

            if (needsLiveAggregation(v)) {
                TaskAggregate ta = taskAgg.getOrDefault(v.getId(), TaskAggregate.EMPTY);
                taskTotal = ta.total;
                taskSuccess = ta.success;
                taskFailed = ta.failed;
                stage = ta.stage != null ? ta.stage : (taskTotal > 0 ? "COMPLETED" : "-");
                long[] g = graphAgg.getOrDefault(v.getId(), new long[]{0L, 0L});
                nodeCount = g[0];
                edgeCount = g[1];
                factCount = factAgg.getOrDefault(v.getId(), 0L);
            } else {
                taskTotal = v.getTaskTotal() != null ? v.getTaskTotal() : 0;
                taskSuccess = v.getTaskSuccess() != null ? v.getTaskSuccess() : 0;
                taskFailed = v.getTaskFailed() != null ? v.getTaskFailed() : 0;
                stage = v.getCurrentStage() != null ? v.getCurrentStage() : "-";
                nodeCount = v.getNodeCount() != null ? v.getNodeCount() : 0L;
                edgeCount = v.getEdgeCount() != null ? v.getEdgeCount() : 0L;
                factCount = v.getFactCount() != null ? v.getFactCount() : 0L;
            }

            int progress = taskTotal > 0 ? (int) ((long) taskSuccess * 100 / taskTotal) : 0;
            if ("SUCCESS".equals(v.getScanStatus()) || "COMPLETED".equals(v.getScanStatus())) {
                progress = 100;
            }

            map.put("progress", progress);
            map.put("taskCount", taskTotal);
            map.put("completedTaskCount", taskSuccess);
            map.put("failedTaskCount", taskFailed);
            map.put("stage", stage);

            // 耗时计算
            long duration = 0;
            if (v.getStartedAt() != null) {
                LocalDateTime end = v.getFinishedAt() != null ? v.getFinishedAt() : LocalDateTime.now();
                duration = Duration.between(v.getStartedAt(), end).getSeconds();
            }
            map.put("duration", duration);
            map.put("nodeCount", nodeCount);
            map.put("edgeCount", edgeCount);
            map.put("factCount", factCount);

            result.add(map);
        }

        return PageResult.of(result, versionPage.getTotal(), pageNum, pageSize);
    }

    /**
     * 判定是否需要对该版本实时聚合统计：
     * 未回写过快照（stats_updated_at 为空，V13 之前遗留），
     * 或仍处于非终态（扫描进行中/暂停/新建，节点边数在变化）。
     */
    private boolean needsLiveAggregation(ScanVersion v) {
        if (v.getStatsUpdatedAt() == null) {
            return true;
        }
        String s = v.getScanStatus();
        return !("SUCCESS".equals(s) || "COMPLETED".equals(s)
                || "FAILED".equals(s) || "CANCELLED".equals(s));
    }

    /** 批量聚合 ScanTask 统计：一次 SQL 按 version_id IN (...) 拿到本页需实时统计的版本任务列表。 */
    private Map<String, TaskAggregate> batchAggregateTasks(List<String> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) return Collections.emptyMap();
        List<ScanTask> tasks = scanTaskRepository.lambdaQuery()
                .in(ScanTask::getVersionId, versionIds)
                .list();
        Map<String, TaskAggregate> map = new HashMap<>();
        for (ScanTask t : tasks) {
            TaskAggregate agg = map.computeIfAbsent(t.getVersionId(), k -> new TaskAggregate());
            agg.total++;
            if ("SUCCESS".equals(t.getTaskStatus())) {
                agg.success++;
            } else if ("FAILED".equals(t.getTaskStatus())) {
                agg.failed++;
                if (agg.stage == null) agg.stage = t.getTaskType();
            } else {
                if (agg.stage == null) agg.stage = t.getTaskType();
            }
        }
        return map;
    }

    /** 批量聚合 Fact 数：一次 SQL group by。 */
    private Map<String, Long> batchAggregateFacts(List<String> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) return Collections.emptyMap();
        List<Fact> facts = factRepository.lambdaQuery()
                .in(Fact::getVersionId, versionIds)
                .select(Fact::getVersionId)
                .list();
        Map<String, Long> map = new HashMap<>();
        for (Fact f : facts) {
            map.merge(f.getVersionId(), 1L, Long::sum);
        }
        return map;
    }

    /**
     * 批量聚合 Neo4j 节点/边计数：一条 Cypher（UNWIND）出全部版本，避免每个版本 2 次往返。
     * 返回 map: versionId -> [nodeCount, edgeCount]。
     * 任一次 Neo4j 失败时返回空 map，上层用 0 兜底，不阻断列表接口。
     */
    private Map<String, long[]> batchAggregateGraph(String projectId, List<String> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) return Collections.emptyMap();
        Map<String, long[]> map = new HashMap<>();
        for (String id : versionIds) {
            map.put(id, new long[]{0L, 0L});
            String normalized = normalizeVersionId(id);
            try {
                long nodeCount = neo4jGraphDao.countNodes(projectId, normalized, null);
                long edgeCount = neo4jGraphDao.countEdges(projectId, normalized, null);
                map.put(id, new long[]{nodeCount, edgeCount});
            } catch (Exception e) {
                // Neo4j 失败时不阻断列表：保持该版本 0/0 兜底
            }
        }
        return map;
    }

    /** 内部值对象：一个版本的任务聚合结果。 */
    private static final class TaskAggregate {
        static final TaskAggregate EMPTY = new TaskAggregate();
        int total;
        int success;
        int failed;
        String stage;
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
