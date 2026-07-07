package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.systemoverview.LayerMappingDTO;
import io.github.legacygraph.dto.systemoverview.SystemOverviewDTO;
import io.github.legacygraph.service.systemoverview.SystemOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统关系总览查询控制器。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段2。
 * 提供业务/功能/代码/数据四层关系的 总览/按域/链路 查询。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/system-overview")
@Tag(name = "系统关系总览", description = "业务/功能/代码/数据四层关系查询")
public class SystemOverviewController {

    private final SystemOverviewService systemOverviewService;

    public SystemOverviewController(SystemOverviewService systemOverviewService) {
        this.systemOverviewService = systemOverviewService;
    }

    /**
     * 全量四层关系总览。
     */
    @GetMapping
    @Operation(summary = "获取系统关系总览", description = "返回 12 业务域的业务↔功能↔代码↔数据映射 + 核心贯穿链路")
    public Result<SystemOverviewDTO> getOverview(
            @Parameter(description = "项目ID", required = true) @PathVariable String projectId,
            @Parameter(description = "扫描版本ID") @RequestParam(required = false) String versionId) {

        return Result.success(systemOverviewService.getOverview(projectId, versionId));
    }

    /**
     * 按业务域查询（模糊匹配业务域或能力名）。
     */
    @GetMapping("/domains/{domainId}")
    @Operation(summary = "按业务域查询映射", description = "按业务域或能力名模糊匹配四层映射")
    public Result<List<LayerMappingDTO>> getDomain(
            @Parameter(description = "项目ID", required = true) @PathVariable String projectId,
            @Parameter(description = "业务域或能力名（模糊匹配）", required = true) @PathVariable String domainId,
            @Parameter(description = "扫描版本ID") @RequestParam(required = false) String versionId) {

        return Result.success(systemOverviewService.getDomain(projectId, versionId, domainId));
    }

    /**
     * 核心贯穿链路（业务→功能→代码→数据）。
     */
    @GetMapping("/paths")
    @Operation(summary = "获取核心贯穿链路", description = "返回业务→功能→代码→数据的核心链路描述")
    public Result<List<String>> getPaths(
            @Parameter(description = "项目ID", required = true) @PathVariable String projectId,
            @Parameter(description = "扫描版本ID") @RequestParam(required = false) String versionId,
            @Parameter(description = "链路起点关键词") @RequestParam(required = false) String from,
            @Parameter(description = "链路终点关键词") @RequestParam(required = false) String to) {

        return Result.success(systemOverviewService.getPaths(projectId, versionId, from, to));
    }
}
