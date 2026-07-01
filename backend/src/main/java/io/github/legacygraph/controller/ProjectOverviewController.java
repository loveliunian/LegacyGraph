package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.ProjectOverviewResponse;
import io.github.legacygraph.service.ProjectOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 项目概览控制器
 */
@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "项目概览", description = "项目概览统计信息")
public class ProjectOverviewController {

    private final ProjectOverviewService projectOverviewService;

    public ProjectOverviewController(ProjectOverviewService projectOverviewService) {
        this.projectOverviewService = projectOverviewService;
    }

    @GetMapping("/overview")
    @Operation(summary = "获取项目概览", description = "获取项目的资料接入状态、图谱统计、最近扫描版本和审核记录")
    public Result<ProjectOverviewResponse> getOverview(
            @PathVariable String projectId) {
        ProjectOverviewResponse response = projectOverviewService.getOverview(projectId);
        return Result.success(response);
    }
}
