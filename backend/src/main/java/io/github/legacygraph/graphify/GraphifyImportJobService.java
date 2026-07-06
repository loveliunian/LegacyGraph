package io.github.legacygraph.graphify;

import io.github.legacygraph.integration.graphify.GraphifyImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Graphify 导入作业服务。
 * <p>
 * 管理导入作业的完整生命周期：创建、启动、完成、失败、重试、取消。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphifyImportJobService {

    private final GraphifyImportJobRepository repository;
    private final GraphifyImportService importService;
    private final GraphifyMetrics metrics;

    /**
     * 创建新的导入作业。
     *
     * @param projectId   项目 ID
     * @param versionId   版本 ID
     * @param projectRoot 项目根目录
     * @param branchName  分支名称
     * @param sourceCommit 源码 commit
     * @return 创建的作业
     */
    public GraphifyImportJob create(String projectId, String versionId,
                                     String projectRoot, String branchName,
                                     String sourceCommit) {
        GraphifyImportJob job = GraphifyImportJob.builder()
                .jobId(UUID.randomUUID().toString())
                .projectId(projectId)
                .versionId(versionId)
                .projectRoot(projectRoot)
                .branchName(branchName)
                .sourceCommit(sourceCommit)
                .status(GraphifyImportJob.Status.QUEUED)
                .createdAt(LocalDateTime.now())
                .build();
        repository.save(job);
        log.info("创建 Graphify 导入作业: jobId={}, projectId={}, versionId={}",
                job.getJobId(), projectId, versionId);
        return job;
    }

    /**
     * 启动作业执行。
     *
     * @param job 作业
     * @return 更新后的作业
     * @throws IllegalStateException 如果作业不在可启动状态
     */
    public GraphifyImportJob start(GraphifyImportJob job) {
        if (job.getStatus() != GraphifyImportJob.Status.QUEUED) {
            throw new IllegalStateException("作业状态不正确，无法启动: " + job.getStatus());
        }
        job.setStatus(GraphifyImportJob.Status.RUNNING);
        job.setAttempt(job.getAttempt() + 1);
        job.setStartedAt(LocalDateTime.now());
        job.setErrorMessage(null);
        repository.save(job);
        log.info("启动 Graphify 导入作业: jobId={}, attempt={}", job.getJobId(), job.getAttempt());
        return job;
    }

    /**
     * 标记作业成功完成。
     *
     * @param job              作业
     * @param importedNodes    导入的节点数
     * @param importedEdges    导入的边数
     * @param importedEvidence 导入的证据数
     * @return 更新后的作业
     */
    public GraphifyImportJob complete(GraphifyImportJob job,
                                       int importedNodes, int importedEdges,
                                       int importedEvidence) {
        job.setStatus(GraphifyImportJob.Status.IMPORTED);
        job.setFinishedAt(LocalDateTime.now());
        job.setImportedNodes(importedNodes);
        job.setImportedEdges(importedEdges);
        job.setImportedEvidence(importedEvidence);
        repository.save(job);
        metrics.recordSuccess(importedNodes, importedEdges, job.getAttempt());
        log.info("Graphify 导入作业完成: jobId={}, nodes={}, edges={}, evidence={}",
                job.getJobId(), importedNodes, importedEdges, importedEvidence);
        return job;
    }

    /**
     * 标记作业失败。
     *
     * @param job          作业
     * @param errorMessage 错误信息
     * @return 更新后的作业
     */
    public GraphifyImportJob fail(GraphifyImportJob job, String errorMessage) {
        job.setStatus(GraphifyImportJob.Status.FAILED);
        job.setFinishedAt(LocalDateTime.now());
        job.setErrorMessage(errorMessage);
        repository.save(job);
        metrics.recordFailure(job.getAttempt());
        log.warn("Graphify 导入作业失败: jobId={}, attempt={}, error={}",
                job.getJobId(), job.getAttempt(), errorMessage);
        return job;
    }

    /**
     * 取消作业。
     *
     * @param job 作业
     * @return 更新后的作业
     * @throws IllegalStateException 如果作业已终止
     */
    public GraphifyImportJob cancel(GraphifyImportJob job) {
        if (job.isTerminated()) {
            throw new IllegalStateException("作业已终止，无法取消: " + job.getStatus());
        }
        job.setStatus(GraphifyImportJob.Status.CANCELLED);
        job.setFinishedAt(LocalDateTime.now());
        repository.save(job);
        log.info("Graphify 导入作业已取消: jobId={}", job.getJobId());
        return job;
    }

    /**
     * 检查作业是否可以重试。
     *
     * @param job 作业
     * @return true 如果可以重试
     */
    public boolean canRetry(GraphifyImportJob job) {
        return job.canRetry();
    }

    /**
     * 重试失败的作业。
     *
     * @param job 作业
     * @return 重置后的作业（状态为 QUEUED）
     * @throws IllegalStateException 如果作业不可重试
     */
    public GraphifyImportJob retry(GraphifyImportJob job) {
        if (!canRetry(job)) {
            throw new IllegalStateException("作业不可重试: status=" + job.getStatus()
                    + ", attempt=" + job.getAttempt() + "/" + job.getMaxAttempts());
        }
        job.setStatus(GraphifyImportJob.Status.QUEUED);
        job.setFinishedAt(null);
        job.setStartedAt(null);
        job.setErrorMessage(null);
        job.setImportedNodes(null);
        job.setImportedEdges(null);
        job.setImportedEvidence(null);
        repository.save(job);
        log.info("Graphify 导入作业已重置为排队状态: jobId={}", job.getJobId());
        return job;
    }

    /**
     * 执行导入作业（同步）。
     *
     * @param job 作业
     * @return 完成后的作业
     */
    public GraphifyImportJob execute(GraphifyImportJob job) {
        start(job);
        long startTime = System.currentTimeMillis();
        try {
            java.nio.file.Path graphJsonPath = java.nio.file.Paths.get(
                    job.getProjectRoot(), "graphify-out", "graph.json");

            var result = importService.importGraph(
                    job.getProjectId(),
                    job.getVersionId(),
                    graphJsonPath
            );

            return complete(job, result.getProcessedNodes(), result.getProcessedEdges(), result.getEvidenceCount());
        } catch (Exception e) {
            metrics.recordDuration(System.currentTimeMillis() - startTime);
            return fail(job, e.getMessage());
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordDuration(duration);
        }
    }

    /**
     * 查找作业。
     *
     * @param jobId 作业 ID
     * @return 作业
     * @throws IllegalArgumentException 如果作业不存在
     */
    public GraphifyImportJob getJob(String jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("作业不存在: " + jobId));
    }

    /**
     * 查找项目的所有作业。
     *
     * @param projectId 项目 ID
     * @return 作业列表
     */
    public List<GraphifyImportJob> getJobsByProject(String projectId) {
        return repository.findByProjectId(projectId);
    }
}
