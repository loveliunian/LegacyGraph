package io.github.legacygraph.eval;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ScanVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Graphify 质量评估服务。
 * <p>
 * 基于实际导入 Neo4j 的 Graphify 节点/边键，对 {@link GraphifyBenchmarkCaseRegistry} 中注册的
 * benchmark 用例评分，聚合为前端 GraphifyQualityDashboard 所需的 {@link GraphifyQualityResult}。
 * 无 benchmark 用例时退化为数据存在性评估，保证接口始终可用。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphifyQualityService {

    /** Graphify 导入节点/边的来源类型，与 GraphifyImportSnapshotService 保持一致。 */
    private static final List<String> GRAPHIFY_SOURCE_TYPES = List.of("GRAPHIFY_AST", "GRAPHIFY_SEMANTIC");

    /** 节点召回率发布门槛，与 {@link GraphifyBenchmarkResult#passesReleaseGate()} 一致。 */
    private static final double NODE_RECALL_THRESHOLD = 0.9;

    private final Neo4jGraphDao graphDao;
    private final GraphifyBenchmarkScorer scorer;
    private final GraphifyBenchmarkCaseRegistry caseRegistry;
    private final ScanVersionRepository scanVersionRepository;

    /**
     * 计算项目的 Graphify 质量结果。
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID，为空时取项目最新扫描版本
     * @return 质量评估结果
     */
    public GraphifyQualityResult getQuality(String projectId, String versionId) {
        String resolvedVersionId = resolveVersionId(projectId, versionId);
        if (resolvedVersionId == null) {
            return new GraphifyQualityResult(0, 0, 0, List.of(), false, "无扫描版本");
        }

        Set<String> nodeKeys = Optional.ofNullable(
                graphDao.queryNodeKeysBySourceTypes(projectId, resolvedVersionId, GRAPHIFY_SOURCE_TYPES))
                .orElse(Set.of());
        Set<String> edgeKeys = Optional.ofNullable(
                graphDao.queryEdgeKeysBySourceTypes(projectId, resolvedVersionId, GRAPHIFY_SOURCE_TYPES))
                .orElse(Set.of());

        List<GraphifyBenchmarkCase> cases = caseRegistry.cases();
        List<GraphifyQualityResult.BenchmarkItem> items = new ArrayList<>();
        double sumNodeRecall = 0.0;
        double sumEdgeRecall = 0.0;
        boolean allPass = true;
        String firstFailReason = null;

        for (GraphifyBenchmarkCase tc : cases) {
            GraphifyBenchmarkResult r = scorer.score(tc, nodeKeys, edgeKeys);
            boolean passed = r.passesReleaseGate();
            if (!passed) {
                allPass = false;
                if (firstFailReason == null) {
                    firstFailReason = r.getReleaseGateDetails();
                }
            }
            items.add(new GraphifyQualityResult.BenchmarkItem(
                    tc.name(), passed, r.nodeRecall(), NODE_RECALL_THRESHOLD, r.getReleaseGateDetails()));
            sumNodeRecall += r.nodeRecall();
            sumEdgeRecall += r.edgeRecall();
        }

        double nodeCoverage;
        double edgeCoverage;
        boolean releaseGatePassed;
        String releaseGateReason;

        if (cases.isEmpty()) {
            // 无 benchmark 样例：退化为数据存在性评估
            nodeCoverage = nodeKeys.isEmpty() ? 0.0 : 1.0;
            edgeCoverage = edgeKeys.isEmpty() ? 0.0 : 1.0;
            boolean hasData = !nodeKeys.isEmpty() || !edgeKeys.isEmpty();
            releaseGatePassed = hasData;
            releaseGateReason = hasData ? null : "无 Graphify 导入数据";
        } else {
            nodeCoverage = sumNodeRecall / cases.size();
            edgeCoverage = sumEdgeRecall / cases.size();
            releaseGatePassed = allPass;
            releaseGateReason = allPass ? null : firstFailReason;
        }

        double overallScore = (nodeCoverage + edgeCoverage) / 2.0 * 100.0;

        log.debug("Graphify quality: projectId={}, versionId={}, cases={}, nodeKeys={}, edgeKeys={}, score={}, gate={}",
                projectId, resolvedVersionId, cases.size(), nodeKeys.size(), edgeKeys.size(),
                overallScore, releaseGatePassed);

        return new GraphifyQualityResult(
                overallScore, nodeCoverage, edgeCoverage, items, releaseGatePassed, releaseGateReason);
    }

    /**
     * 解析版本 ID：显式传入则直接使用，否则取项目最新扫描版本。
     */
    private String resolveVersionId(String projectId, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            return versionId;
        }
        List<ScanVersion> latest = scanVersionRepository.lambdaQuery()
                .eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getCreatedAt)
                .last("LIMIT 1")
                .list();
        return latest.isEmpty() ? null : latest.get(0).getId();
    }
}
