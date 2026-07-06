package io.github.legacygraph.graphify;

import io.github.legacygraph.dao.Neo4jGraphDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Graphify 回滚服务测试。
 */
class GraphifyRollbackServiceTest {

    private GraphifyImportJobRepository repository;
    private GraphifyRollbackService rollbackService;

    @BeforeEach
    void setUp() {
        repository = new GraphifyImportJobRepository();
        rollbackService = new GraphifyRollbackService(repository);
    }

    @Test
    @DisplayName("回滚已完成的作业应该删除导入的数据")
    void rollbackCompletedJobShouldRemoveImportedData() {
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        rollbackService = new GraphifyRollbackService(repository, graphDao);
        GraphifyImportJob job = GraphifyImportJob.builder()
                .jobId("job-1")
                .projectId("proj-1")
                .versionId("ver-1")
                .status(GraphifyImportJob.Status.IMPORTED)
                .importedNodes(100)
                .importedEdges(50)
                .importedEvidence(75)
                .build();
        repository.save(job);
        when(graphDao.deleteGraphifyClaims("proj-1", "ver-1"))
                .thenReturn(new Neo4jGraphDao.GraphifyDeleteResult(100, 50));

        GraphifyRollbackService.RollbackResult result = rollbackService.rollback("job-1");

        assertEquals("job-1", result.jobId());
        assertEquals(100, result.removedNodes());
        assertEquals(50, result.removedEdges());
        assertEquals(75, result.removedEvidence());

        GraphifyImportJob updatedJob = repository.findById("job-1").orElseThrow();
        assertEquals(GraphifyImportJob.Status.CANCELLED, updatedJob.getStatus());
        verify(graphDao).deleteGraphifyClaims("proj-1", "ver-1");
    }

    @Test
    @DisplayName("回滚失败的作业应该成功")
    void rollbackFailedJobShouldSucceed() {
        GraphifyImportJob job = GraphifyImportJob.builder()
                .jobId("job-2")
                .projectId("proj-1")
                .versionId("ver-1")
                .status(GraphifyImportJob.Status.FAILED)
                .importedNodes(0)
                .importedEdges(0)
                .importedEvidence(0)
                .build();
        repository.save(job);

        GraphifyRollbackService.RollbackResult result = rollbackService.rollback("job-2");

        assertEquals("job-2", result.jobId());
        assertEquals(0, result.removedNodes());
    }

    @Test
    @DisplayName("回滚正在运行的作业应该抛出异常")
    void rollbackRunningJobShouldThrowException() {
        GraphifyImportJob job = GraphifyImportJob.builder()
                .jobId("job-3")
                .projectId("proj-1")
                .versionId("ver-1")
                .status(GraphifyImportJob.Status.RUNNING)
                .build();
        repository.save(job);

        assertThrows(IllegalStateException.class, () -> rollbackService.rollback("job-3"));
    }

    @Test
    @DisplayName("回滚不存在的作业应该抛出异常")
    void rollbackNonExistentJobShouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> rollbackService.rollback("non-existent"));
    }

    @Test
    @DisplayName("回滚没有导入数据的作业应该返回零")
    void rollbackJobWithNoImportedDataShouldReturnZero() {
        GraphifyImportJob job = GraphifyImportJob.builder()
                .jobId("job-4")
                .projectId("proj-1")
                .versionId("ver-1")
                .status(GraphifyImportJob.Status.IMPORTED)
                .build();
        repository.save(job);

        GraphifyRollbackService.RollbackResult result = rollbackService.rollback("job-4");

        assertEquals(0, result.removedNodes());
        assertEquals(0, result.removedEdges());
        assertEquals(0, result.removedEvidence());
    }
}
