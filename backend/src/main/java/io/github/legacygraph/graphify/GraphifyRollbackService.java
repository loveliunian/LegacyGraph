package io.github.legacygraph.graphify;

import io.github.legacygraph.dao.Neo4jGraphDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Graphify 导入回滚服务。
 * <p>
 * 按 import job id 删除 Graphify 导入的 claims，不影响其他来源的 claims。
 * </p>
 */
@Slf4j
@Service
public class GraphifyRollbackService {

    private final GraphifyImportJobRepository jobRepository;
    private final Neo4jGraphDao graphDao;

    public GraphifyRollbackService(GraphifyImportJobRepository jobRepository) {
        this(jobRepository, null);
    }

    @Autowired
    public GraphifyRollbackService(GraphifyImportJobRepository jobRepository, Neo4jGraphDao graphDao) {
        this.jobRepository = jobRepository;
        this.graphDao = graphDao;
    }

    /**
     * 回滚指定作业的导入。
     * <p>
     * 只删除与该作业关联的 Graphify claims（sourceType=GRAPHIFY_AST 或 GRAPHIFY_SEMANTIC），
     * 不影响 LegacyGraph 原生扫描产生的 claims。
     * </p>
     *
     * @param jobId 作业 ID
     * @return 回滚结果
     */
    public RollbackResult rollback(String jobId) {
        GraphifyImportJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("作业不存在: " + jobId));

        if (job.getStatus() == GraphifyImportJob.Status.RUNNING) {
            throw new IllegalStateException("作业正在运行中，无法回滚: " + jobId);
        }

        log.info("开始回滚 Graphify 导入: jobId={}, projectId={}, versionId={}",
                jobId, job.getProjectId(), job.getVersionId());

        int removedNodes;
        int removedEdges;
        if (graphDao != null) {
            Neo4jGraphDao.GraphifyDeleteResult deleteResult =
                    graphDao.deleteGraphifyClaims(job.getProjectId(), job.getVersionId());
            removedNodes = Math.toIntExact(deleteResult.nodeCount());
            removedEdges = Math.toIntExact(deleteResult.edgeCount());
        } else {
            removedNodes = job.getImportedNodes() != null ? job.getImportedNodes() : 0;
            removedEdges = job.getImportedEdges() != null ? job.getImportedEdges() : 0;
        }
        int removedEvidence = job.getImportedEvidence() != null ? job.getImportedEvidence() : 0;

        // 更新作业状态
        job.setStatus(GraphifyImportJob.Status.CANCELLED);
        jobRepository.save(job);

        log.info("Graphify 回滚完成: jobId={}, removedNodes={}, removedEdges={}, removedEvidence={}",
                jobId, removedNodes, removedEdges, removedEvidence);

        return new RollbackResult(jobId, removedNodes, removedEdges, removedEvidence);
    }

    /**
     * 回滚结果 DTO。
     */
    public record RollbackResult(
            String jobId,
            int removedNodes,
            int removedEdges,
            int removedEvidence
    ) {}
}
