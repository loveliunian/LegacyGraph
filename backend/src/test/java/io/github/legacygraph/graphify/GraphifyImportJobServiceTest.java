package io.github.legacygraph.graphify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Graphify 导入作业服务测试。
 */
class GraphifyImportJobServiceTest {

    private GraphifyImportJobRepository repository;
    private GraphifyMetrics metrics;
    private GraphifyImportJobService service;

    @BeforeEach
    void setUp() {
        repository = new GraphifyImportJobRepository();
        metrics = new GraphifyMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new GraphifyImportJobService(repository, null, metrics);
    }

    @Test
    @DisplayName("创建作业应该返回 QUEUED 状态")
    void createJobShouldReturnQueuedStatus() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path/to/project", "main", "abc123");

        assertNotNull(job.getJobId());
        assertEquals("proj-1", job.getProjectId());
        assertEquals("ver-1", job.getVersionId());
        assertEquals(GraphifyImportJob.Status.QUEUED, job.getStatus());
        assertNotNull(job.getCreatedAt());
        assertEquals(0, job.getAttempt());
    }

    @Test
    @DisplayName("启动作业应该增加尝试次数并设置为 RUNNING")
    void startJobShouldIncrementAttemptAndSetRunning() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");
        GraphifyImportJob started = service.start(job);

        assertEquals(GraphifyImportJob.Status.RUNNING, started.getStatus());
        assertEquals(1, started.getAttempt());
        assertNotNull(started.getStartedAt());
    }

    @Test
    @DisplayName("完成作业应该设置为 IMPORTED 并记录导入数量")
    void completeJobShouldSetImportedAndRecordCounts() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");
        service.start(job);
        GraphifyImportJob completed = service.complete(job, 100, 50, 75);

        assertEquals(GraphifyImportJob.Status.IMPORTED, completed.getStatus());
        assertNotNull(completed.getFinishedAt());
        assertEquals(100, completed.getImportedNodes());
        assertEquals(50, completed.getImportedEdges());
        assertEquals(75, completed.getImportedEvidence());
    }

    @Test
    @DisplayName("失败作业应该设置为 FAILED 并记录错误信息")
    void failJobShouldSetFailedAndRecordError() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");
        service.start(job);
        GraphifyImportJob failed = service.fail(job, "Timeout occurred");

        assertEquals(GraphifyImportJob.Status.FAILED, failed.getStatus());
        assertNotNull(failed.getFinishedAt());
        assertEquals("Timeout occurred", failed.getErrorMessage());
    }

    @Test
    @DisplayName("失败作业在达到最大尝试次数前可以重试")
    void failedJobCanBeRetriedBeforeMaxAttempts() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");
        service.start(job);
        GraphifyImportJob failed = service.fail(job, "Error");

        assertTrue(service.canRetry(failed));
        GraphifyImportJob retried = service.retry(failed);

        assertEquals(GraphifyImportJob.Status.QUEUED, retried.getStatus());
        assertNull(retried.getStartedAt());
        assertNull(retried.getFinishedAt());
        assertNull(retried.getErrorMessage());
    }

    @Test
    @DisplayName("失败作业在达到最大尝试次数后不能重试")
    void failedJobCannotBeRetriedAfterMaxAttempts() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");

        // 模拟 3 次失败
        service.start(job);
        service.fail(job, "Error 1");
        service.retry(job);

        service.start(job);
        service.fail(job, "Error 2");
        service.retry(job);

        service.start(job);
        GraphifyImportJob finalFailed = service.fail(job, "Error 3");

        assertFalse(service.canRetry(finalFailed));
        assertThrows(IllegalStateException.class, () -> service.retry(finalFailed));
    }

    @Test
    @DisplayName("取消排队中的作业应该设置为 CANCELLED")
    void cancelQueuedJobShouldSetCancelled() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");
        GraphifyImportJob cancelled = service.cancel(job);

        assertEquals(GraphifyImportJob.Status.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getFinishedAt());
    }

    @Test
    @DisplayName("取消已终止的作业应该抛出异常")
    void cancelTerminatedJobShouldThrowException() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");
        service.start(job);
        service.complete(job, 10, 5, 8);

        assertThrows(IllegalStateException.class, () -> service.cancel(job));
    }

    @Test
    @DisplayName("启动非 QUEUED 状态的作业应该抛出异常")
    void startNonQueuedJobShouldThrowException() {
        GraphifyImportJob job = service.create("proj-1", "ver-1", "/path", "main", "abc");
        service.start(job);

        assertThrows(IllegalStateException.class, () -> service.start(job));
    }

    @Test
    @DisplayName("获取项目作业列表应该返回所有作业")
    void getJobsByProjectShouldReturnAllJobs() {
        service.create("proj-1", "ver-1", "/path1", "main", "abc");
        service.create("proj-1", "ver-2", "/path2", "dev", "def");
        service.create("proj-2", "ver-3", "/path3", "main", "ghi");

        var jobs = service.getJobsByProject("proj-1");
        assertEquals(2, jobs.size());
    }

    @Test
    @DisplayName("获取不存在的作业应该抛出异常")
    void getNonExistentJobShouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> service.getJob("non-existent-id"));
    }
}
