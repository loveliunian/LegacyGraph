package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.federation.CrossRepoDiffService;
import io.github.legacygraph.federation.CrossRepoImpactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 跨仓库影响分析 API。
 * <p>
 * 基于 Neo4j 图谱数据发现跨项目/仓库之间的依赖链路与影响传播关系。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/graphify/cross-repo-impact")
@RequiredArgsConstructor
public class CrossRepoImpactController {

    private final CrossRepoImpactService crossRepoImpactService;
    private final CrossRepoDiffService crossRepoDiffService;
    private final Neo4jGraphDao neo4jGraphDao;

    /**
     * 获取跨仓库影响链路。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID（可选）
     * @return 包含影响链路列表的响应
     */
    @GetMapping
    public Result<Map<String, List<CrossRepoImpactService.CrossRepoImpactChain>>> getCrossRepoImpact(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId) {

        log.info("跨仓库影响分析请求: projectId={}, versionId={}", projectId, versionId);

        List<CrossRepoImpactService.CrossRepoImpactChain> chains =
                crossRepoImpactService.getCrossRepoImpact(projectId, versionId);

        return Result.ok(Map.of("list", chains));
    }

    /**
     * H03: 跨仓 graph.json 差异对比 — 计算 LegacyGraph 节点与 Graphify graph.json 的对齐率。
     *
     * <p>复用 GraphifyRunner 已产出的 graph.json，与本项目 Neo4j 节点做 Jaccard diff，
     * 返回 alignmentRate + missingInGraphify + missingInLegacy。</p>
     *
     * @param projectId     项目 ID
     * @param versionId     版本 ID（可选，默认取最新版本）
     * @param graphJsonPath graph.json 文件路径
     * @return 跨仓差异报告
     */
    @GetMapping("/diff")
    public Result<CrossRepoDiffService.CrossRepoDiffReport> diffAgainstGraphify(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId,
            @RequestParam String graphJsonPath) {

        log.info("跨仓差异对比: projectId={}, versionId={}, graphJsonPath={}", projectId, versionId, graphJsonPath);

        // 从 Neo4j 查询本项目全量节点（limit=0 表示不限制）
        List<GraphNode> legacyNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, null, null, null, null, 0);

        CrossRepoDiffService.CrossRepoDiffReport report =
                crossRepoDiffService.diffWithLegacyNodes(graphJsonPath, projectId, versionId, legacyNodes);

        return Result.ok(report);
    }
}
