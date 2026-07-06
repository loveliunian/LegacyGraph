package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestResult;
import io.github.legacygraph.service.systemoverview.SystemOverviewIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统关系总览事实底座导入控制器。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段1。
 * 把业务-功能-代码-数据四层关系写入向量/Claim/语义缓存，让 EnhancedQaAgent 能回答关系问题。
 * </p>
 * <p>
 * 权限：沿用 SecurityConfig + JWT 模式（现有 Controller 不用 @PreAuthorize）。
 * 导入为写操作，默认要求已认证用户。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/system-overview")
@Tag(name = "系统关系总览", description = "业务/功能/代码/数据四层关系事实底座导入")
public class SystemOverviewIngestController {

    private final SystemOverviewIngestService ingestService;

    public SystemOverviewIngestController(SystemOverviewIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * 导入请求中的结构化关系与 FAQ。
     * 幂等：Claim 按 key 去重，向量 upsert，语义缓存按 question 去重。
     */
    @PostMapping("/ingest")
    @Operation(summary = "导入系统关系总览事实底座",
            description = "把业务能力映射行写入向量/Claim，把 FAQ 写入语义缓存。EnhancedQaAgent 底座填充后自动生效。")
    public Result<SystemOverviewIngestResult> ingest(
            @Parameter(description = "导入请求（projectId/versionId/relations/faqs）", required = true)
            @RequestBody SystemOverviewIngestRequest request) {

        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            return Result.badRequest("projectId is required");
        }
        SystemOverviewIngestResult result = ingestService.ingest(request);
        return Result.success(result);
    }

    /**
     * 一键导入内置系统关系总览底座（来自 02 §0.1 十二业务域 + 03 Part A 核心 FAQ）。
     * 用于初始化 LegacyGraph 自身的关系知识底座。
     */
    @PostMapping("/ingest-builtins")
    @Operation(summary = "导入内置系统关系总览底座",
            description = "内置 12 业务域映射 + 核心 FAQ，一键初始化关系知识底座。")
    public Result<SystemOverviewIngestResult> ingestBuiltins(
            @Parameter(description = "项目ID（默认 self）", required = false)
            @RequestParam(defaultValue = "self") String projectId,
            @Parameter(description = "扫描版本ID（默认 default）", required = false)
            @RequestParam(required = false) String versionId) {

        SystemOverviewIngestResult result = ingestService.ingestBuiltins(projectId, versionId);
        return Result.success(result);
    }
}
