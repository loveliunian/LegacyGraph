package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.PrTask;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.service.change.ChangeTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 变更任务中心控制器（增强版2）— 见 doc §新增 API。
 * <p>
 * 提供 bugfix/refactor/upgrade 任务的创建、影响子图刷新、补丁生成、门禁登记。
 * PR 创建链路留待 PrOrchestrator（可用版）。
 * </p>
 */
@RestController
@RequestMapping("/change-tasks")
@Tag(name = "变更任务中心", description = "创建任务、影响子图、生成补丁、验证门禁")
public class ChangeTaskController {

    private final ChangeTaskService changeTaskService;

    public ChangeTaskController(ChangeTaskService changeTaskService) {
        this.changeTaskService = changeTaskService;
    }

    /** 创建 bugfix/refactor/upgrade 任务 */
    @Log(value = "创建变更任务", type = Log.OperationType.CREATE)
    @PostMapping
    @Operation(summary = "创建变更任务")
    public Result<ChangeTask> create(@RequestBody CreateChangeTaskRequest req) {
        ChangeTask task = changeTaskService.createTask(
                req.getProjectId(), req.getVersionId(), req.getTaskType(),
                req.getTitle(), req.getInputIssue());
        return Result.success(task);
    }

    /** 查看任务、影响子图、Patch、验证门禁 */
    @GetMapping
    @Operation(summary = "查询项目变更任务")
    public Result<List<ChangeTask>> list(@RequestParam String projectId) {
        return Result.success(changeTaskService.listTasks(projectId));
    }

    /** 查看任务、影响子图、Patch、验证门禁 */
    @GetMapping("/{id}")
    @Operation(summary = "查看变更任务")
    public Result<ChangeTask> get(@PathVariable String id) {
        ChangeTask task = changeTaskService.getTask(id);
        return task != null ? Result.success(task) : Result.error("任务不存在: " + id);
    }

    /** 基于图谱刷新 impacted subgraph */
    @PostMapping("/{id}/impact")
    @Operation(summary = "刷新影响子图")
    public Result<ImpactSubgraph> refreshImpact(@PathVariable String id,
                                                @RequestBody ImpactRequest req) {
        return Result.success(changeTaskService.refreshImpact(id, req.getTargetNodeId()));
    }

    /** 生成补丁草案 */
    @PostMapping("/{id}/generate-patch")
    @Operation(summary = "生成补丁草案")
    public Result<PatchPlan> generatePatch(@PathVariable String id,
                                           @RequestBody ChangeTaskService.PatchGenRequest req) {
        return Result.success(changeTaskService.generatePatch(id, req));
    }

    /** 登记验证门禁（VALIDATING） */
    @PostMapping("/{id}/register-gates")
    @Operation(summary = "登记验证门禁")
    public Result<List<ValidationGate>> registerGates(@PathVariable String id,
                                                      @RequestBody ValidationRequest req) {
        return Result.success(changeTaskService.registerGates(id, req.getGateTypes()));
    }

    /** 执行验证门禁并驱动任务状态（VALIDATION_PASSED/FAILED） */
    @PostMapping("/{id}/run-validation")
    @Operation(summary = "执行验证门禁")
    public Result<ChangeTask> runValidation(@PathVariable String id,
                                            @RequestBody RunValidationRequest req) {
        if (req.getGateTypes() != null && !req.getGateTypes().isEmpty()) {
            changeTaskService.registerGates(id, req.getGateTypes());
        }
        return Result.success(changeTaskService.runValidation(
                id, req.getCaseIds(), req.getWorkingDir(), req.getEnvironment()));
    }

    /** 创建 PR 草案（未过门禁将被拒绝） */
    @PostMapping("/{id}/create-pr")
    @Operation(summary = "创建 PR 草案")
    public Result<PrTask> createPr(@PathVariable String id) {
        try {
            return Result.success(changeTaskService.createPr(id));
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 标记变更任务对应的 PR 已合并 */
    @PostMapping("/{id}/merge")
    @Operation(summary = "标记变更任务对应的 PR 已合并")
    public Result<ChangeTask> merge(@PathVariable String id) {
        ChangeTask task = changeTaskService.onPrMerged(id);
        return Result.success(task);
    }

    @Data
    public static class CreateChangeTaskRequest {
        private String projectId;
        private String versionId;
        /** BUGFIX / REFACTOR / UPGRADE */
        private String taskType;
        private String title;
        private String inputIssue;
    }

    @Data
    public static class ImpactRequest {
        private String targetNodeId;
    }

    @Data
    public static class ValidationRequest {
        private List<String> gateTypes;
    }

    @Data
    public static class RunValidationRequest {
        /** 可选：先登记这些门禁再执行 */
        private List<String> gateTypes;
        /** 测试类门禁要跑的用例ID */
        private List<String> caseIds;
        /** 命令类门禁工作目录 */
        private String workingDir;
        /** 测试环境 dev/test/prod */
        private String environment;
    }
}
