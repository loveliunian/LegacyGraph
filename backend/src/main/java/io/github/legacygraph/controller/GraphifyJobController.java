package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.graphify.GraphifyImportJob;
import io.github.legacygraph.graphify.GraphifyImportJobService;
import io.github.legacygraph.graphify.GraphifyRollbackService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Graphify 导入作业 REST 控制器。
 * <p>
 * 提供导入作业的创建、查询、重试、取消和回滚功能。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/graphify/jobs")
@RequiredArgsConstructor
public class GraphifyJobController {

    private final GraphifyImportJobService jobService;
    private final GraphifyRollbackService rollbackService;

    /**
     * 创建新的导入作业。
     *
     * @param projectId 项目 ID
     * @param request   创建请求
     * @return 创建的作业
     */
    @PostMapping
    public Result<GraphifyImportJob> createJob(
            @PathVariable String projectId,
            @RequestBody CreateJobRequest request) {
        log.info("创建 Graphify 导入作业: projectId={}", projectId);
        GraphifyImportJob job = jobService.create(
                projectId,
                request.getVersionId(),
                request.getProjectRoot(),
                request.getBranchName(),
                request.getSourceCommit()
        );
        return Result.ok(job);
    }

    /**
     * 获取项目的所有作业。
     *
     * @param projectId 项目 ID
     * @return 作业列表
     */
    @GetMapping
    public Result<List<GraphifyImportJob>> listJobs(@PathVariable String projectId) {
        List<GraphifyImportJob> jobs = jobService.getJobsByProject(projectId);
        return Result.ok(jobs);
    }

    /**
     * 获取单个作业详情。
     *
     * @param projectId 项目 ID
     * @param jobId     作业 ID
     * @return 作业详情
     */
    @GetMapping("/{jobId}")
    public Result<GraphifyImportJob> getJob(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        GraphifyImportJob job = jobService.getJob(jobId);
        return Result.ok(job);
    }

    /**
     * 重试失败的作业。
     *
     * @param projectId 项目 ID
     * @param jobId     作业 ID
     * @return 重置后的作业
     */
    @PostMapping("/{jobId}/retry")
    public Result<GraphifyImportJob> retryJob(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        log.info("重试 Graphify 导入作业: projectId={}, jobId={}", projectId, jobId);
        GraphifyImportJob job = jobService.getJob(jobId);
        GraphifyImportJob retried = jobService.retry(job);
        return Result.ok(retried);
    }

    /**
     * 取消作业。
     *
     * @param projectId 项目 ID
     * @param jobId     作业 ID
     * @return 取消后的作业
     */
    @PostMapping("/{jobId}/cancel")
    public Result<GraphifyImportJob> cancelJob(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        log.info("取消 Graphify 导入作业: projectId={}, jobId={}", projectId, jobId);
        GraphifyImportJob job = jobService.getJob(jobId);
        GraphifyImportJob cancelled = jobService.cancel(job);
        return Result.ok(cancelled);
    }

    /**
     * 回滚作业导入的数据。
     *
     * @param projectId 项目 ID
     * @param jobId     作业 ID
     * @return 回滚结果
     */
    @PostMapping("/{jobId}/rollback")
    public Result<GraphifyRollbackService.RollbackResult> rollbackJob(
            @PathVariable String projectId,
            @PathVariable String jobId) {
        log.info("回滚 Graphify 导入作业: projectId={}, jobId={}", projectId, jobId);
        GraphifyRollbackService.RollbackResult result = rollbackService.rollback(jobId);
        return Result.ok(result);
    }

    /**
     * 创建作业请求 DTO。
     */
    @Data
    public static class CreateJobRequest {
        private String versionId;
        private String projectRoot;
        private String branchName;
        private String sourceCommit;
    }
}
