package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.integration.graphify.GraphifyImportService;
import io.github.legacygraph.service.systemoverview.SelfAnalysisBootstrapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自身分析控制器 — 触发 LegacyGraph 自身扫描数据导入系统图谱。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段4。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/self-analysis")
@Tag(name = "自身分析", description = "LegacyGraph 自身扫描数据导入系统图谱")
public class SelfAnalysisController {

    private final SelfAnalysisBootstrapService bootstrapService;

    public SelfAnalysisController(SelfAnalysisBootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    /**
     * 触发自身分析导入（默认 graphify-out/graph.json）。
     */
    @PostMapping("/bootstrap")
    @Operation(summary = "触发自身分析导入", description = "把 graphify-out/graph.json 导入系统图谱（Neo4j），让 QA 可检索自身知识")
    public Result<GraphifyImportService.ImportResult> bootstrap(
            @Parameter(description = "项目ID（默认 self）") @RequestParam(defaultValue = "self") String projectId,
            @Parameter(description = "扫描版本ID（默认 default）") @RequestParam(required = false) String versionId,
            @Parameter(description = "graph.json 路径（为空用默认 graphify-out/graph.json）") @RequestParam(required = false) String graphJsonPath) {

        GraphifyImportService.ImportResult result = (graphJsonPath == null || graphJsonPath.isBlank())
                ? bootstrapService.bootstrapDefault(projectId, versionId)
                : bootstrapService.bootstrap(projectId, versionId, graphJsonPath);
        return Result.success(result);
    }
}
