package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.event.ScanCompletedEvent;
import io.github.legacygraph.repository.ProjectRepository;
import io.github.legacygraph.service.graph.CrossTypeBlockingService;
import io.github.legacygraph.service.graph.GraphMergeService;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 图谱合并调度器 — 扫描后置 + 增量触发（graph-merge-optimization-plan.md §4.1 P5）。
 * <p>
 * 两条触发路径：
 * <ul>
 *   <li><b>定时全量</b>：每日 03:00 执行，遍历所有项目做全量合并（不限 LLM 预算，走业务域默认类型）</li>
 *   <li><b>事件增量</b>：监听 {@link ScanCompletedEvent}，仅对触发扫描的项目做增量合并，
 *       限制 LLM 调用 ≤ 200 次（计划目标"单 project 全量合并 LLM 调用 ≤ 200 次"）</li>
 * </ul>
 * </p>
 *
 * <p>同时调用 {@link CrossTypeBlockingService} 产出跨类型 POSSIBLE_SAME_AS 候选边，
 * 由前端人工裁决，避免误并。</p>
 *
 * <p>注：调度任务以 @Async 执行，避免阻塞扫描完成事件的主流程。</p>
 */
@Slf4j
@Service
public class GraphMergeScheduler {

    /** 增量合并的 LLM 预算上限（计划目标：单 project 全量合并 LLM 调用 ≤ 200 次） */
    private static final int INCREMENTAL_LLM_BUDGET = 200;

    /** 跨类型候选产出上限，防止巨量疑似同义边淹没前端 */
    private static final int MAX_CROSS_TYPE_CANDIDATES = 200;

    private final GraphMergeService graphMergeService;
    private final CrossTypeBlockingService crossTypeBlockingService;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ProjectRepository projectRepository;

    public GraphMergeScheduler(GraphMergeService graphMergeService,
                                CrossTypeBlockingService crossTypeBlockingService,
                                Neo4jGraphDao neo4jGraphDao,
                                ProjectRepository projectRepository) {
        this.graphMergeService = graphMergeService;
        this.crossTypeBlockingService = crossTypeBlockingService;
        this.neo4jGraphDao = neo4jGraphDao;
        this.projectRepository = projectRepository;
    }

    // ==================== 定时全量合并 ====================

    /**
     * 每日 03:00 全量合并入口。
     * <p>遍历所有项目，对业务域类型执行合并；LLM 预算不限（≤0 表示不限制）。</p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Async
    public void dailyFullMerge() {
        log.info("GraphMergeScheduler: daily full merge triggered at 03:00");
        List<Project> projects;
        try {
            projects = projectRepository.selectList(new LambdaQueryWrapper<Project>());
        } catch (Exception e) {
            log.warn("GraphMergeScheduler: failed to list projects: {}", e.getMessage(), e);
            return;
        }
        if (projects == null || projects.isEmpty()) {
            log.info("GraphMergeScheduler: no projects to merge, skip");
            return;
        }

        int totalAutoMerged = 0;
        int totalCrossType = 0;
        for (Project project : projects) {
            String projectId = project.getId();
            try {
                GraphMergeService.MergeRunResult result =
                        graphMergeService.runMergeForProject(projectId, null, 0);
                totalAutoMerged += result.getTotalAutoMerged();

                // 跨类型候选桥接（POSSIBLE_SAME_AS 边）
                totalCrossType += generateCrossTypeCandidates(projectId);
            } catch (Exception e) {
                log.warn("GraphMergeScheduler: daily merge failed for project={}: {}",
                        projectId, e.getMessage(), e);
            }
        }
        log.info("GraphMergeScheduler: daily full merge done, {} projects, {} auto-merged, {} cross-type candidates",
                projects.size(), totalAutoMerged, totalCrossType);
    }

    // ==================== 扫描完成事件增量合并 ====================

    /**
     * 监听扫描完成事件 — 仅对触发扫描的项目做增量合并，限 200 次 LLM 调用。
     * <p>
     * 触发条件：扫描成功完成后异步执行；失败/异常不影响扫描主流程。
     * </p>
     */
    @EventListener
    @Async
    public void onScanCompleted(ScanCompletedEvent event) {
        String projectId = event.getProjectId();
        String versionId = event.getVersionId();
        log.info("GraphMergeScheduler: incremental merge triggered by ScanCompletedEvent " +
                        "(project={}, version={}, nodes={}, edges={})",
                projectId, versionId, event.getNodeCount(), event.getEdgeCount());

        try {
            // 增量合并：仅业务域类型 + LLM 预算 200
            GraphMergeService.MergeRunResult result =
                    graphMergeService.runMergeForProject(projectId, null, INCREMENTAL_LLM_BUDGET);
            log.info("GraphMergeScheduler: incremental merge done for project={}: " +
                            "{} candidates, {} auto-merged, {} review, {} rejected, LLM={}, took={}ms",
                    projectId, result.getTotalCandidates(), result.getTotalAutoMerged(),
                    result.getTotalReview(), result.getTotalRejected(),
                    result.getLlmCallsUsed(), result.getDurationMs());

            // 跨类型候选桥接
            int crossTypeCount = generateCrossTypeCandidates(projectId);
            log.info("GraphMergeScheduler: generated {} cross-type candidates for project={}",
                    crossTypeCount, projectId);
        } catch (Exception e) {
            log.warn("GraphMergeScheduler: incremental merge failed for project={}: {}",
                    projectId, e.getMessage(), e);
        }
    }

    // ==================== 跨类型候选桥接（POSSIBLE_SAME_AS 边） ====================

    /**
     * 生成跨类型候选并写入 POSSIBLE_SAME_AS 边（待人工裁决）。
     * <p>每对候选只创建一次边（edgeKey 去重），失败不抛异常。</p>
     *
     * @return 实际创建的候选边数量
     */
    private int generateCrossTypeCandidates(String projectId) {
        List<CrossTypeBlockingService.CrossTypeCandidate> candidates;
        try {
            candidates = crossTypeBlockingService.findCrossTypeCandidates(projectId);
        } catch (Exception e) {
            log.warn("GraphMergeScheduler: findCrossTypeCandidates failed for project={}: {}",
                    projectId, e.getMessage(), e);
            return 0;
        }
        if (candidates == null || candidates.isEmpty()) return 0;

        int created = 0;
        for (CrossTypeBlockingService.CrossTypeCandidate c : candidates) {
            if (created >= MAX_CROSS_TYPE_CANDIDATES) {
                log.info("GraphMergeScheduler: cross-type candidate cap reached ({}) for project={}, skip remaining",
                        MAX_CROSS_TYPE_CANDIDATES, projectId);
                break;
            }
            try {
                if (createPossibleSameAsEdge(projectId, c)) {
                    created++;
                }
            } catch (Exception e) {
                log.debug("GraphMergeScheduler: create POSSIBLE_SAME_AS edge failed for pair ({}, {}): {}",
                        c.getNodeAId(), c.getNodeBId(), e.getMessage());
            }
        }
        return created;
    }

    /**
     * 创建一条 POSSIBLE_SAME_AS 候选边（PENDING_CONFIRM 状态）。
     * <p>edgeKey 按 id 字典序拼接保证对称去重；confidence 取语义相似度。</p>
     *
     * @return true 表示创建成功
     */
    private boolean createPossibleSameAsEdge(String projectId, CrossTypeBlockingService.CrossTypeCandidate c) {
        GraphNode nodeA = neo4jGraphDao.findNodeById(c.getNodeAId()).orElse(null);
        GraphNode nodeB = neo4jGraphDao.findNodeById(c.getNodeBId()).orElse(null);
        if (nodeA == null || nodeB == null) return false;

        String versionId = nodeA.getVersionId() != null ? nodeA.getVersionId() : nodeB.getVersionId();
        // edgeKey 按 id 字典序对称拼接，保证 (A,B) 与 (B,A) 一致
        String edgeKey = c.getNodeAId().compareTo(c.getNodeBId()) <= 0
                ? c.getNodeAId() + "->possible_same_as->" + c.getNodeBId()
                : c.getNodeBId() + "->possible_same_as->" + c.getNodeAId();

        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(c.getNodeAId());
        edge.setToNodeId(c.getNodeBId());
        edge.setEdgeType(EdgeType.POSSIBLE_SAME_AS.name());
        edge.setEdgeKey(edgeKey);
        edge.setSourceType("AI_INFERENCE");
        edge.setConfidence(BigDecimal.valueOf(c.getSemanticScore()));
        edge.setStatus(NodeStatus.PENDING_CONFIRM.name());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        edge.setProperties(String.format("{\"reason\":\"%s\",\"sharedEvidence\":%d,\"sharedAlias\":%d}",
                escapeJson(c.getReason()), c.getSharedEvidenceCount(), c.getSharedAliasCount()));

        neo4jGraphDao.createEdge(edge);
        return true;
    }

    /** 简单 JSON 字符串转义（避免引入额外依赖） */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
