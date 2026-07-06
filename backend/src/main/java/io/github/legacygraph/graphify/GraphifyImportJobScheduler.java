package io.github.legacygraph.graphify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Graphify 导入作业调度器。
 * <p>
 * 定期检查排队中的作业并执行。生产环境可替换为更复杂的分布式调度方案。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphifyImportJobScheduler {

    private final GraphifyImportJobRepository repository;
    private final GraphifyImportJobService jobService;

    /**
     * 定期处理排队中的作业。
     * <p>
     * 每 30 秒检查一次，处理每个项目最早的一个排队作业。
     * </p>
     */
    @Scheduled(fixedDelay = 30000)
    public void processQueuedJobs() {
        log.debug("检查排队中的 Graphify 导入作业...");
        List<GraphifyImportJob> queuedJobs = repository.findQueued(10);
        if (queuedJobs.isEmpty()) {
            return;
        }
        for (GraphifyImportJob job : queuedJobs) {
            try {
                jobService.execute(job);
            } catch (Exception e) {
                log.warn("Graphify 导入作业执行失败: jobId={}, error={}", job.getJobId(), e.getMessage());
            }
        }
    }

    /**
     * 手动触发执行指定作业。
     *
     * @param jobId 作业 ID
     * @return 执行后的作业
     */
    public GraphifyImportJob executeNow(String jobId) {
        GraphifyImportJob job = jobService.getJob(jobId);
        if (job.getStatus() != GraphifyImportJob.Status.QUEUED) {
            throw new IllegalStateException("只能执行排队中的作业: status=" + job.getStatus());
        }
        return jobService.execute(job);
    }

    /**
     * 执行项目中下一个排队作业。
     *
     * @param projectId 项目 ID
     * @return 执行的作业，如果没有排队作业则返回 null
     */
    public GraphifyImportJob executeNext(String projectId) {
        List<GraphifyImportJob> queued = repository.findQueuedByProjectId(projectId);
        if (queued.isEmpty()) {
            log.debug("项目 {} 没有排队中的 Graphify 导入作业", projectId);
            return null;
        }
        GraphifyImportJob next = queued.getFirst();
        log.info("执行项目 {} 的下一个 Graphify 导入作业: {}", projectId, next.getJobId());
        return jobService.execute(next);
    }
}
