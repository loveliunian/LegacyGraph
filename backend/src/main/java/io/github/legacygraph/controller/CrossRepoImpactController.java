package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
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
}
