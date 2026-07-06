package io.github.legacygraph.graphify;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Graphify 导入作业仓储（内存实现）。
 * <p>
 * 生产环境应替换为 PostgreSQL 持久化实现。
 * </p>
 */
@Repository
public class GraphifyImportJobRepository {

    private final Map<String, GraphifyImportJob> jobs = new ConcurrentHashMap<>();

    /**
     * 保存作业。
     *
     * @param job 作业实体
     * @return 保存后的作业
     */
    public GraphifyImportJob save(GraphifyImportJob job) {
        jobs.put(job.getJobId(), job);
        return job;
    }

    /**
     * 根据 ID 查找作业。
     *
     * @param jobId 作业 ID
     * @return 作业（如果存在）
     */
    public Optional<GraphifyImportJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * 查找项目的所有作业（按创建时间倒序）。
     *
     * @param projectId 项目 ID
     * @return 作业列表
     */
    public List<GraphifyImportJob> findByProjectId(String projectId) {
        List<GraphifyImportJob> result = new ArrayList<>();
        for (GraphifyImportJob job : jobs.values()) {
            if (job.getProjectId().equals(projectId)) {
                result.add(job);
            }
        }
        result.sort((a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });
        return result;
    }

    /**
     * 查找项目的排队作业。
     *
     * @param projectId 项目 ID
     * @return 排队作业列表
     */
    public List<GraphifyImportJob> findQueuedByProjectId(String projectId) {
        List<GraphifyImportJob> result = new ArrayList<>();
        for (GraphifyImportJob job : jobs.values()) {
            if (job.getProjectId().equals(projectId)
                    && job.getStatus() == GraphifyImportJob.Status.QUEUED) {
                result.add(job);
            }
        }
        return result;
    }

    /**
     * 查找全局排队作业（按创建时间升序，先入队先执行）。
     */
    public List<GraphifyImportJob> findQueued(int limit) {
        List<GraphifyImportJob> result = new ArrayList<>();
        for (GraphifyImportJob job : jobs.values()) {
            if (job.getStatus() == GraphifyImportJob.Status.QUEUED) {
                result.add(job);
            }
        }
        result.sort((a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return a.getCreatedAt().compareTo(b.getCreatedAt());
        });
        return result.subList(0, Math.min(limit, result.size()));
    }

    /**
     * 查找最近的作业（按创建时间倒序）。
     *
     * @param limit 最大返回数量
     * @return 最近的作业列表
     */
    public List<GraphifyImportJob> findRecent(int limit) {
        List<GraphifyImportJob> all = new ArrayList<>(jobs.values());
        all.sort((a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });
        return all.subList(0, Math.min(limit, all.size()));
    }

    /**
     * 删除作业。
     *
     * @param job 作业实体
     */
    public void delete(GraphifyImportJob job) {
        jobs.remove(job.getJobId());
    }

    /**
     * 获取所有作业数量。
     *
     * @return 作业总数
     */
    public long count() {
        return jobs.size();
    }
}
