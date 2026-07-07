package io.github.legacygraph.service.change;

import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.service.qa.SemanticCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字段增量回写服务 — ADD_COLUMN 任务验证通过后，把新 Column 节点 + HAS_COLUMN 边写入图谱。
 * <p>
 * 复用 {@link EvidenceGraphWriter#upsertNode} / {@link EvidenceGraphWriter#upsertEdge}
 * （非 Neo4jGraphDao.mergeNode/mergeEdge），走统一写图路径（证据继承 + INCOMPLETE 补偿）。
 * </p>
 * <p>对齐 doc/项目升级计划/QA变更影响问答打通详细设计.md §4.4</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColumnIngestService {

    private final EvidenceGraphWriter evidenceGraphWriter;
    private final Neo4jGraphDao neo4jGraphDao;
    private final SemanticCache semanticCache;

    /** 解析 Flyway 脚本中的 ALTER TABLE <table> ADD COLUMN <column> <type> */
    private static final Pattern ALTER_ADD_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)\\s+([A-Za-z]+(?:\\s*\\([^)]*\\))?)",
            Pattern.CASE_INSENSITIVE);

    /**
     * ADD_COLUMN 任务验证通过后的闭环：回写 Column 节点 + HAS_COLUMN 边 + 失效语义缓存。
     * <p>异常不阻断主流程（验证已通过，回写失败由 GraphWriteReconciler 补偿）。</p>
     */
    public void onValidationPassed(ChangeTask task, PatchPlan plan) {
        if (task == null || !"ADD_COLUMN".equals(task.getTaskType())) return;
        try {
            ingestFromPlan(task.getProjectId(), task.getVersionId(), plan);
        } catch (Exception e) {
            log.warn("Column ingest failed for task {}: {}", task.getId(), e.getMessage());
        }
        try {
            semanticCache.invalidateByProject(task.getProjectId());
        } catch (Exception e) {
            log.warn("Cache invalidation failed for project {}: {}", task.getProjectId(), e.getMessage());
        }
    }

    /**
     * 从 PatchPlan 的 patches 中找 Flyway 脚本，解析 ALTER TABLE ADD COLUMN，回写图谱。
     */
    public void ingestFromPlan(String projectId, String versionId, PatchPlan plan) {
        if (plan == null || plan.getPatches() == null) return;
        for (PatchPlan.Patch patch : plan.getPatches()) {
            if (patch.getFilePath() == null || patch.getPatchText() == null) continue;
            if (!patch.getFilePath().endsWith(".sql")) continue;
            Matcher m = ALTER_ADD_PATTERN.matcher(patch.getPatchText());
            while (m.find()) {
                String tableName = m.group(1);
                String columnName = m.group(2);
                String columnType = m.group(3).trim();
                ingestAddedColumn(projectId, versionId, tableName, columnName, columnType);
            }
        }
        log.warn("ColumnIngest: no ALTER TABLE ADD COLUMN found in plan patches for project {}", projectId);
    }

    /**
     * 增量写入新字段到图谱（Column 节点 + HAS_COLUMN 边）。
     * <p>HAS_COLUMN 边类型已存在于 EdgeType.java（非新增）。</p>
     */
    public void ingestAddedColumn(String projectId, String versionId,
                                  String tableName, String columnName, String columnType) {
        String normalizedVersionId = versionId != null ? versionId.replace("-", "") : null;
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, normalizedVersionId, "Table", null, null, null, 200);
        GraphNode table = tables.stream()
                .filter(t -> tableName.equalsIgnoreCase(t.getNodeName()))
                .findFirst().orElse(null);
        if (table == null) {
            log.warn("ColumnIngest: Table node not found: {}", tableName);
            return;
        }

        // 1. upsert Column 节点（sourcePath 触发自动 PRIMARY_SOURCE 证据；sourceType=DB_METADATA → CONFIRMED）
        GraphNodeClaim columnClaim = GraphNodeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .nodeType("Column")
                .nodeKey(tableName + "." + columnName)
                .nodeName(columnName)
                .displayName(columnName)
                .description("字段 " + columnName + " " + columnType + " on " + tableName)
                .sourceType("DB_METADATA")
                .sourcePath("db/" + tableName + "/" + columnName)
                .confidence(new BigDecimal("0.85"))
                .status("CONFIRMED")
                .build();
        GraphNode columnNode = evidenceGraphWriter.upsertNode(columnClaim);
        log.info("Column ingested: table={}, column={}, nodeId={}", tableName, columnName, columnNode.getId());

        // 2. upsert HAS_COLUMN 边（upsertEdge 端点不存在时静默返回未合并 edge，由 EvidenceGraphWriter 内部补偿）
        GraphEdgeClaim hasColumnClaim = GraphEdgeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .fromNodeId(table.getId())
                .toNodeId(columnNode.getId())
                .edgeType("HAS_COLUMN")
                .edgeKey(table.getId() + "->" + columnNode.getId() + ":HAS_COLUMN")
                .sourceType("DB_METADATA")
                .confidence(new BigDecimal("0.85"))
                .status("CONFIRMED")
                .build();
        GraphEdge edge = evidenceGraphWriter.upsertEdge(hasColumnClaim);
        log.info("HAS_COLUMN edge upserted: table={}, column={}, edgeId={}",
                tableName, columnName, edge != null ? edge.getId() : "null");
    }
}
